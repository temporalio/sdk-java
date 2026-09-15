package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationFailedException;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.client.UntypedNexusServiceClient;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.FailureConverter;
import io.temporal.failure.DefaultFailureConverter;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.SerializationContext;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.EchoNexusServiceImpl;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Coverage that the standalone Nexus client scopes its data converter to the endpoint, service and
 * operation of the operation it is acting on, and that a handle obtained by operation ID — which
 * has no endpoint, service or operation — serializes without a context instead.
 *
 * <p>Standalone Nexus operations require a real server with them enabled.
 */
public class StandaloneNexusSerializationContextTest {
  private static final String SERVICE = "TestNexusService1";
  private static final String OPERATION = "operation";

  // Only the standalone client gets this codec. The worker keeps the default converter, so every
  // context this codec records is one the client applied rather than the handler.
  private static final RecordingCodec CODEC = new RecordingCodec();
  private static final RecordingFailureConverter FAILURE_CONVERTER =
      new RecordingFailureConverter();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(PlaceholderWorkflowImpl.class)
          .setNexusServiceImplementation(new EchoNexusServiceImpl())
          .build();

  private NexusClient nexusClient() {
    return NexusClient.newInstance(
        testWorkflowRule.getWorkflowServiceStubs(),
        NexusClientOptions.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setDataConverter(
                new CodecDataConverter(
                    DefaultDataConverter.newDefaultInstance()
                        .withFailureConverter(FAILURE_CONVERTER),
                    Collections.singletonList(CODEC)))
            .build());
  }

  @Before
  public void requireStandaloneNexusSupport() {
    assumeTrue(
        "server does not support standalone Nexus operations",
        testWorkflowRule.isUseExternalService());
    CODEC.reset();
    FAILURE_CONVERTER.reset();
  }

  @Test
  public void startedHandleUsesItsStartRequestContext() {
    String input = "ping-" + UUID.randomUUID();
    UntypedNexusOperationHandle handle = startOperation(input);

    // Decoding the result correctly is itself the assertion: the codec rejects a payload whose
    // recorded context does not match the context it is being decoded under.
    Assert.assertEquals("echo:" + input, handle.getResult(String.class));
    Assert.assertTrue(
        "the start input and the polled result should both use the operation's context, but saw "
            + CODEC.nexusContexts(),
        CODEC.nexusContexts().contains(expectedContext()));
  }

  @Test
  public void failureUsesTheOperationsContext() {
    UntypedNexusOperationHandle handle =
        startOperation(EchoNexusServiceImpl.FAIL_PREFIX + UUID.randomUUID());
    // Ignore what the start request itself converted, so only the failure path is observed.
    FAILURE_CONVERTER.reset();

    Assert.assertThrows(NexusOperationFailedException.class, () -> handle.getResult(String.class));
    Assert.assertEquals(
        "the operation failure should be converted under the operation's context",
        Collections.singletonList(expectedContext()),
        FAILURE_CONVERTER.nexusContexts());
  }

  @Test
  public void describeUsesContextFromTheResponse() {
    String input = "ping-" + UUID.randomUUID();
    UntypedNexusOperationHandle handle = startOperation(input);
    handle.getResult(String.class);
    CODEC.reset();

    // A description decodes its payloads lazily, so reading one is what exercises the converter it
    // was built with.
    NexusOperationExecutionDescription description = handle.describe();
    Assert.assertEquals(
        java.util.Optional.of("echo:" + input), description.getResult(String.class));

    Assert.assertTrue(
        "describe should build the context from the endpoint, service and operation the server "
            + "reports, but saw "
            + CODEC.allContexts(),
        CODEC.nexusContexts().contains(expectedContext()));
  }

  @Test
  public void describeDecodesTheLastAttemptFailureWithContext() {
    UntypedNexusOperationHandle handle =
        startOperation(EchoNexusServiceImpl.FAIL_PREFIX + UUID.randomUUID());
    Assert.assertThrows(NexusOperationFailedException.class, () -> handle.getResult(String.class));
    FAILURE_CONVERTER.reset();

    NexusOperationExecutionDescription description = handle.describe();
    Assert.assertNotNull("expected a terminal failure to describe", description.getFailure());

    Assert.assertEquals(
        "the described failure should be converted under the context the server reported",
        Collections.singletonList(expectedContext()),
        FAILURE_CONVERTER.nexusContexts());
  }

  @Test
  public void handleObtainedByIdHasNoContext() {
    String input = "ping-" + UUID.randomUUID();
    UntypedNexusOperationHandle started = startOperation(input);
    started.getResult(String.class);
    CODEC.reset();

    // A handle obtained by ID never saw a start request, so there is no endpoint, service or
    // operation to scope its converter by.
    UntypedNexusOperationHandle detached =
        nexusClient().getHandle(started.getNexusOperationId(), started.getNexusOperationRunId());
    Assert.assertEquals("echo:" + input, detached.getResult(String.class));

    Assert.assertEquals(
        "a handle obtained by operation ID should decode without a Nexus context",
        Collections.emptyList(),
        CODEC.nexusContexts());
  }

  private NexusSerializationContext expectedContext() {
    return new NexusSerializationContext(
        testWorkflowRule.getNexusEndpoint().getSpec().getName(), SERVICE, OPERATION);
  }

  private UntypedNexusOperationHandle startOperation(String input) {
    NexusClient client = nexusClient();
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    UntypedNexusServiceClient serviceClient =
        client.newUntypedNexusServiceClient(endpoint.getSpec().getName(), SERVICE);
    StartNexusOperationOptions options =
        StartNexusOperationOptions.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setScheduleToCloseTimeout(Duration.ofSeconds(30))
            .build();
    return serviceClient.start(OPERATION, options, input);
  }

  public static class PlaceholderWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      return input;
    }
  }

  /** Records the Nexus contexts the SDK scopes failure conversion by. */
  private static class RecordingFailureConverter implements FailureConverter {
    private final List<SerializationContext> seen;
    private final SerializationContext context;
    private final FailureConverter delegate = new DefaultFailureConverter();

    RecordingFailureConverter() {
      this(Collections.synchronizedList(new ArrayList<>()), null);
    }

    private RecordingFailureConverter(
        List<SerializationContext> seen, SerializationContext context) {
      this.seen = seen;
      this.context = context;
    }

    void reset() {
      seen.clear();
    }

    List<NexusSerializationContext> nexusContexts() {
      List<NexusSerializationContext> result = new ArrayList<>();
      synchronized (seen) {
        for (SerializationContext each : seen) {
          if (each instanceof NexusSerializationContext) {
            result.add((NexusSerializationContext) each);
          }
        }
      }
      return result;
    }

    @Override
    @Nonnull
    public FailureConverter withContext(@Nonnull SerializationContext context) {
      return new RecordingFailureConverter(seen, context);
    }

    @Override
    @Nonnull
    public RuntimeException failureToException(
        @Nonnull io.temporal.api.failure.v1.Failure failure, @Nonnull DataConverter dataConverter) {
      seen.add(context);
      return delegate.failureToException(failure, dataConverter);
    }

    @Override
    @Nonnull
    public io.temporal.api.failure.v1.Failure exceptionToFailure(
        @Nonnull Throwable throwable, @Nonnull DataConverter dataConverter) {
      seen.add(context);
      return delegate.exceptionToFailure(throwable, dataConverter);
    }
  }

  /**
   * Records the Nexus contexts it is handed, and tags each payload it encodes with the context
   * used, refusing to decode a payload under a context other than the one that encoded it.
   */
  private static class RecordingCodec implements PayloadCodec {
    private static final String SIGNATURE_KEY = "ser-ctx-signature";

    // Shared by every instance derived via withContext, so a test sees all contexts that were used.
    private final List<SerializationContext> seen;
    private final SerializationContext context;

    RecordingCodec() {
      this(Collections.synchronizedList(new ArrayList<>()), null);
    }

    private RecordingCodec(List<SerializationContext> seen, SerializationContext context) {
      this.seen = seen;
      this.context = context;
    }

    void reset() {
      seen.clear();
    }

    List<SerializationContext> allContexts() {
      synchronized (seen) {
        return new ArrayList<>(seen);
      }
    }

    List<NexusSerializationContext> nexusContexts() {
      List<NexusSerializationContext> result = new ArrayList<>();
      synchronized (seen) {
        for (SerializationContext each : seen) {
          if (each instanceof NexusSerializationContext) {
            result.add((NexusSerializationContext) each);
          }
        }
      }
      return result;
    }

    @Override
    @Nonnull
    public PayloadCodec withContext(@Nonnull SerializationContext context) {
      return new RecordingCodec(seen, context);
    }

    @Override
    @Nonnull
    public List<Payload> encode(@Nonnull List<Payload> payloads) {
      seen.add(context);
      if (!(context instanceof NexusSerializationContext)) {
        return payloads;
      }
      NexusSerializationContext nexus = (NexusSerializationContext) context;
      String signature =
          nexus.getEndpoint() + ":" + nexus.getService() + ":" + nexus.getOperation();
      List<Payload> encoded = new ArrayList<>(payloads.size());
      for (Payload payload : payloads) {
        encoded.add(
            Payload.newBuilder(payload)
                .putMetadata(SIGNATURE_KEY, ByteString.copyFromUtf8(signature))
                .build());
      }
      return encoded;
    }

    @Override
    @Nonnull
    public List<Payload> decode(@Nonnull List<Payload> payloads) {
      seen.add(context);
      List<Payload> decoded = new ArrayList<>(payloads.size());
      for (Payload payload : payloads) {
        ByteString signature = payload.getMetadataMap().get(SIGNATURE_KEY);
        if (signature == null) {
          // Payloads encoded without a context stay readable, as the contract requires.
          decoded.add(payload);
          continue;
        }
        if (context instanceof NexusSerializationContext) {
          NexusSerializationContext nexus = (NexusSerializationContext) context;
          Assert.assertEquals(
              "payload should be decoded under the context it was encoded with",
              nexus.getEndpoint() + ":" + nexus.getService() + ":" + nexus.getOperation(),
              signature.toStringUtf8());
        }
        decoded.add(Payload.newBuilder(payload).removeMetadata(SIGNATURE_KEY).build());
      }
      return decoded;
    }
  }
}
