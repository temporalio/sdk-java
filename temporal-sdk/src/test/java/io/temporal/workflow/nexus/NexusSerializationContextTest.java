package io.temporal.workflow.nexus;

import com.google.protobuf.ByteString;
import io.nexusrpc.OperationException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.api.nexus.v1.EndpointSpec;
import io.temporal.api.nexus.v1.EndpointTarget;
import io.temporal.api.operatorservice.v1.CreateNexusEndpointRequest;
import io.temporal.api.operatorservice.v1.DeleteNexusEndpointRequest;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.SerializationContext;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestNexusServices;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * End-to-end coverage that a workflow calling a Nexus operation encodes its input, decodes its
 * result, and converts its failures under the endpoint, service and operation of that operation.
 *
 * <p>Two endpoints are routed to the same worker so that a single workflow can call both and the
 * payloads for each can be told apart on the wire.
 *
 * <p>Nexus requires a real server, so these are skipped unless {@code USE_EXTERNAL_SERVICE=true}.
 */
public class NexusSerializationContextTest {
  // Unique per run. Fixed names are left behind by a run that dies before tearDown and then make
  // every later run fail to create them.
  private static final String RED_ENDPOINT = "red-nexus-endpoint-" + UUID.randomUUID();
  private static final String BLUE_ENDPOINT = "blue-nexus-endpoint-" + UUID.randomUUID();
  private static final String SERVICE = "TestNexusService1";
  private static final String OPERATION = "operation";

  private static final SigningCodec CODEC = new SigningCodec();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TwoEndpointWorkflowImpl.class, FailingWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .setWorkflowClientOptions(
              io.temporal.client.WorkflowClientOptions.newBuilder()
                  .setDataConverter(
                      new CodecDataConverter(
                          DefaultDataConverter.STANDARD_INSTANCE, Collections.singletonList(CODEC)))
                  .build())
          .build();

  private final List<Endpoint> endpoints = new ArrayList<>();

  @Before
  public void setUp() {
    Assume.assumeTrue(
        "Nexus operations require a real server", SDKTestWorkflowRule.useExternalService);
    CODEC.reset();
    endpoints.add(createEndpoint(RED_ENDPOINT));
    endpoints.add(createEndpoint(BLUE_ENDPOINT));
  }

  @After
  public void tearDown() {
    for (Endpoint endpoint : endpoints) {
      testWorkflowRule
          .getTestEnvironment()
          .getOperatorServiceStubs()
          .blockingStub()
          .deleteNexusEndpoint(
              DeleteNexusEndpointRequest.newBuilder()
                  .setId(endpoint.getId())
                  .setVersion(endpoint.getVersion())
                  .build());
    }
    endpoints.clear();
  }

  @Test
  public void inputAndResultUseTheOperationsOwnContext() {
    TwoEndpointWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TwoEndpointWorkflow.class);
    // Each operation must come back with the value it was called with, which only happens if the
    // result was decoded under the same context it was encoded with.
    Assert.assertEquals(Arrays.asList("Hello, red!", "Hello, blue!"), workflow.execute());
    String workflowId = WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId();

    // The payloads on the wire carry the context each operation was scheduled with.
    Map<String, String> inputSignatures = new HashMap<>();
    Map<String, String> resultSignatures = new HashMap<>();
    Map<Long, String> scheduledEndpoints = new HashMap<>();
    for (HistoryEvent event : testWorkflowRule.getExecutionHistory(workflowId).getEvents()) {
      if (event.hasNexusOperationScheduledEventAttributes()) {
        io.temporal.api.history.v1.NexusOperationScheduledEventAttributes attrs =
            event.getNexusOperationScheduledEventAttributes();
        scheduledEndpoints.put(event.getEventId(), attrs.getEndpoint());
        inputSignatures.put(attrs.getEndpoint(), signatureOf(attrs.getInput()));
      } else if (event.hasNexusOperationCompletedEventAttributes()) {
        io.temporal.api.history.v1.NexusOperationCompletedEventAttributes attrs =
            event.getNexusOperationCompletedEventAttributes();
        resultSignatures.put(
            scheduledEndpoints.get(attrs.getScheduledEventId()), signatureOf(attrs.getResult()));
      }
    }

    Assert.assertEquals(
        "each operation's input should be encoded under its own endpoint",
        expectedSignatures(),
        inputSignatures);
    Assert.assertEquals(
        "each operation's result should be encoded under its own endpoint",
        expectedSignatures(),
        resultSignatures);
  }

  @Test
  public void failuresUseTheOperationsOwnContext() {
    FailingWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(FailingWorkflow.class);
    Assert.assertThrows(WorkflowFailedException.class, workflow::execute);

    NexusSerializationContext expected =
        new NexusSerializationContext(RED_ENDPOINT, SERVICE, OPERATION);
    Assert.assertTrue(
        "the caller should have converted the operation failure under the operation's context, "
            + "but saw "
            + CODEC.nexusContexts(),
        CODEC.nexusContexts().contains(expected));
  }

  private Map<String, String> expectedSignatures() {
    Map<String, String> expected = new HashMap<>();
    expected.put(RED_ENDPOINT, signature(RED_ENDPOINT));
    expected.put(BLUE_ENDPOINT, signature(BLUE_ENDPOINT));
    return expected;
  }

  private static String signatureOf(Payload payload) {
    ByteString signature = payload.getMetadataMap().get(SigningCodec.SIGNATURE_KEY);
    return signature == null ? null : signature.toStringUtf8();
  }

  private static String signature(String endpoint) {
    return endpoint + ":" + SERVICE + ":" + OPERATION;
  }

  private Endpoint createEndpoint(String name) {
    return testWorkflowRule
        .getTestEnvironment()
        .getOperatorServiceStubs()
        .blockingStub()
        .createNexusEndpoint(
            CreateNexusEndpointRequest.newBuilder()
                .setSpec(
                    EndpointSpec.newBuilder()
                        .setName(name)
                        .setTarget(
                            EndpointTarget.newBuilder()
                                .setWorker(
                                    EndpointTarget.Worker.newBuilder()
                                        .setNamespace(
                                            testWorkflowRule.getTestEnvironment().getNamespace())
                                        .setTaskQueue(testWorkflowRule.getTaskQueue()))))
                .build())
        .getEndpoint();
  }

  @WorkflowInterface
  public interface TwoEndpointWorkflow {
    @WorkflowMethod
    List<String> execute();
  }

  @WorkflowInterface
  public interface FailingWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class TwoEndpointWorkflowImpl implements TwoEndpointWorkflow {
    @Override
    public List<String> execute() {
      return Arrays.asList(
          stubFor(RED_ENDPOINT).operation("red"), stubFor(BLUE_ENDPOINT).operation("blue"));
    }
  }

  public static class FailingWorkflowImpl implements FailingWorkflow {
    @Override
    public void execute() {
      stubFor(RED_ENDPOINT).operation("fail");
    }
  }

  private static TestNexusServices.TestNexusService1 stubFor(String endpoint) {
    return Workflow.newNexusServiceStub(
        TestNexusServices.TestNexusService1.class,
        NexusServiceOptions.newBuilder()
            .setEndpoint(endpoint)
            .setOperationOptions(
                NexusOperationOptions.newBuilder()
                    .setScheduleToCloseTimeout(Duration.ofSeconds(20))
                    .build())
            .build());
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (ctx, details, name) -> {
            if ("fail".equals(name)) {
              // Details give the failure real payloads, so the codec is consulted and the strict
              // decode check below can compare the handler's context with the caller's.
              throw OperationException.failed(
                  ApplicationFailure.newNonRetryableFailure(
                      "operation failed on purpose", "ContextFailure", "failure-detail"));
            }
            return "Hello, " + name + "!";
          });
    }
  }

  /**
   * Stamps the Nexus context it was given onto every payload it encodes, so the context used for a
   * payload can be read back off the wire, and records the Nexus contexts it was handed.
   */
  private static class SigningCodec implements PayloadCodec {
    static final String SIGNATURE_KEY = "ser-ctx-signature";

    // Shared by every instance derived via withContext, so a test sees all contexts that were used.
    private final List<SerializationContext> seen;
    private final SerializationContext context;

    SigningCodec() {
      this(Collections.synchronizedList(new ArrayList<>()), null);
    }

    private SigningCodec(List<SerializationContext> seen, SerializationContext context) {
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
    public PayloadCodec withContext(@Nonnull SerializationContext context) {
      return new SigningCodec(seen, context);
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
        // This codec only signs under a Nexus context, so a signed payload was encoded under one.
        // Decoding it without one means the two halves of the round trip disagreed.
        Assert.assertTrue(
            "payload encoded under a Nexus context was decoded under " + context,
            context instanceof NexusSerializationContext);
        NexusSerializationContext nexus = (NexusSerializationContext) context;
        Assert.assertEquals(
            "payload should be decoded under the context it was encoded with",
            nexus.getEndpoint() + ":" + nexus.getService() + ":" + nexus.getOperation(),
            signature.toStringUtf8());
        decoded.add(Payload.newBuilder(payload).removeMetadata(SIGNATURE_KEY).build());
      }
      return decoded;
    }
  }
}
