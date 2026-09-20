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
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.FailureConverter;
import io.temporal.failure.DefaultFailureConverter;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.SerializationContext;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
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
import org.junit.experimental.categories.Category;

/**
 * Coverage that the standalone Nexus client scopes its data converter to the endpoint, service and
 * operation of the operation it is acting on, and that a handle obtained by operation ID — which
 * has no endpoint, service or operation — serializes without a context instead.
 *
 * <p>Standalone Nexus operations require a real server with them enabled.
 */
@CloudTestExclusionNote(
    "Cloud CI does not provision the standalone Nexus permissions required by this test.")
@Category(RequiresCloudProvisioning.class)
public class StandaloneNexusSerializationContextTest {
  private static final String SERVICE = "TestNexusService1";
  private static final String OPERATION = "operation";

  // Both sides share the codec so a signature written by one is checked by the other. Any payload
  // the SDK encodes and decodes under different contexts therefore fails the decode, the way a
  // codec keyed on the context would. Only the client gets the recording failure converter, so the
  // contexts it records are the client's.
  private static final RecordingCodec CODEC = new RecordingCodec();

  // A handle obtained by operation ID decodes a context-encoded payload without a context, so
  // that direction is only an error when a test says it should be.
  private static boolean allowContextlessDecodeOfSignedPayload;
  private static final RecordingFailureConverter FAILURE_CONVERTER =
      new RecordingFailureConverter();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(PlaceholderWorkflowImpl.class)
          .setNexusServiceImplementation(new EchoNexusServiceImpl())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(
                      new CodecDataConverter(
                          DefaultDataConverter.STANDARD_INSTANCE, Collections.singletonList(CODEC)))
                  .build())
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
    allowContextlessDecodeOfSignedPayload = false;
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
  public void describeReadsTheSummaryUnderTheOperationsContext() {
    NexusClient client = nexusClient();
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    UntypedNexusServiceClient serviceClient =
        client.newUntypedNexusServiceClient(endpoint.getSpec().getName(), SERVICE);
    UntypedNexusOperationHandle handle =
        serviceClient.start(
            OPERATION,
            StartNexusOperationOptions.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                .setSummary("the-summary")
                .build(),
            "ping-" + UUID.randomUUID());
    handle.getResult(String.class);

    // User metadata is serialized with the operation's context, so describe has to read it back
    // under the same one. The strict codec below fails either half of a context mismatch.
    Assert.assertEquals("the-summary", handle.describe().getStaticSummary());
  }

  @Test
  public void handleObtainedByIdHasNoContext() {
    allowContextlessDecodeOfSignedPayload = true;
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
          // Decoding under a Nexus context something that was encoded without one means the SDK
          // picked different contexts for the two halves of a round trip. A codec keyed on the
          // context, such as a per-endpoint encryption key, could not recover this payload.
          Assert.assertFalse(
              "payload encoded without a context was decoded under " + context,
              context instanceof NexusSerializationContext);
          decoded.add(payload);
          continue;
        }
        if (!(context instanceof NexusSerializationContext)) {
          // The reverse mismatch: encoded under a context, decoded without one. Expected only
          // for a handle obtained by operation ID, which opts in below.
          Assert.assertTrue(
              "payload encoded under a Nexus context was decoded under " + context,
              allowContextlessDecodeOfSignedPayload);
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
