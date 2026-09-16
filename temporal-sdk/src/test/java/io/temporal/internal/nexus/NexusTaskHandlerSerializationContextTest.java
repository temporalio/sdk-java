package io.temporal.internal.nexus;

import static org.mockito.Mockito.mock;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.Duration;
import io.nexusrpc.OperationException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.nexus.v1.Request;
import io.temporal.api.nexus.v1.StartOperationRequest;
import io.temporal.api.workflowservice.v1.PollNexusTaskQueueResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.worker.NexusTask;
import io.temporal.internal.worker.NexusTaskHandler;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.SerializationContext;
import io.temporal.workflow.shared.TestNexusServices;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that the Nexus task handler scopes its data converter to the endpoint, service and
 * operation the inbound request names, for operation input, synchronous results and failures.
 */
public class NexusTaskHandlerSerializationContextTest {
  private static final String NAMESPACE = "testNamespace";
  private static final String TASK_QUEUE = "testTaskQueue";
  private static final String ENDPOINT = "handler-endpoint";
  private static final String SERVICE = "TestNexusService1";
  private static final String OPERATION = "operation";

  private Scope metricsScope;

  @Before
  public void setUp() {
    metricsScope =
        new RootScopeBuilder().reporter(new TestStatsReporter()).reportEvery(Duration.ofMillis(10));
  }

  @Test
  public void inputAndSyncResultUseOperationContext() throws TimeoutException {
    NexusSerializationContext expected =
        new NexusSerializationContext(ENDPOINT, SERVICE, OPERATION);
    // A separate converter stands in for the caller, so the contexts recorded by the handler's own
    // codec are only the ones the handler used.
    DataConverter callerConverter = signingConverter(new SigningCodec());
    SigningCodec handlerCodec = new SigningCodec();
    DataConverter handlerConverter = signingConverter(handlerCodec);

    // The caller encodes the input under the operation's context, so the handler has to decode it
    // under the same context to read it back.
    Payload input = callerConverter.withContext(expected).toPayload("handler-input").get();

    NexusTaskHandler.Result result =
        handle(handlerConverter, new EchoServiceImpl(), startTask(input));

    Assert.assertNull(result.getHandlerException());
    Payload resultPayload = result.getResponse().getStartOperation().getSyncSuccess().getPayload();
    Assert.assertEquals(
        "the sync result should be encoded under the operation's context",
        signature(expected),
        resultPayload.getMetadataOrThrow(SigningCodec.SIGNATURE_KEY).toStringUtf8());
    Assert.assertEquals(
        "Hello, handler-input!",
        callerConverter
            .withContext(expected)
            .fromPayload(resultPayload, String.class, String.class));
    Assert.assertEquals(
        "the handler should decode the input and encode the result under the operation's context",
        java.util.Arrays.asList(expected, expected),
        handlerCodec.contexts());
  }

  @Test
  public void operationFailureUsesOperationContext() throws TimeoutException {
    NexusSerializationContext expected =
        new NexusSerializationContext(ENDPOINT, SERVICE, OPERATION);
    DataConverter callerConverter = signingConverter(new SigningCodec());
    SigningCodec handlerCodec = new SigningCodec();
    DataConverter handlerConverter = signingConverter(handlerCodec);
    Payload input = callerConverter.withContext(expected).toPayload("boom").get();

    NexusTaskHandler.Result result =
        handle(handlerConverter, new FailingServiceImpl(), startTask(input));

    Assert.assertNull(result.getHandlerException());
    Failure failure = result.getResponse().getStartOperation().getFailure();
    Assert.assertNotEquals(
        "the operation should have reported a failure", Failure.getDefaultInstance(), failure);
    Assert.assertTrue(
        "the failure conversion should have reached the codec; if it did not, this test cannot "
            + "distinguish a contextual failure encode from a contextless one",
        handlerCodec.contexts().size() > 1);
    Assert.assertEquals(
        "every converter call the handler made should be under the operation's context",
        Collections.singleton(expected),
        new java.util.HashSet<>(handlerCodec.contexts()));
  }

  @Test
  public void serializerWithoutTaskInScopeUsesContextlessConverter() {
    // The serializer is shared by the whole worker and is also reachable outside of a Nexus task,
    // where there is no endpoint/service/operation to scope it by.
    SigningCodec codec = new SigningCodec();
    DataConverter dataConverter = signingConverter(codec);

    PayloadSerializer serializer = new PayloadSerializer(dataConverter);
    serializer.serialize("no-task-in-scope");

    Assert.assertEquals(
        "no Nexus task is in scope, so the codec should be called without a context",
        Collections.singletonList(null),
        codec.contexts());
  }

  private static DataConverter signingConverter(SigningCodec codec) {
    return new CodecDataConverter(
        DefaultDataConverter.STANDARD_INSTANCE, Collections.singletonList(codec));
  }

  private NexusTaskHandler.Result handle(
      DataConverter dataConverter, Object serviceImpl, PollNexusTaskQueueResponse.Builder task)
      throws TimeoutException {
    NexusTaskHandlerImpl handler =
        new NexusTaskHandlerImpl(
            mock(WorkflowClient.class),
            NAMESPACE,
            TASK_QUEUE,
            dataConverter,
            new WorkerInterceptor[] {});
    handler.registerNexusServiceImplementations(new Object[] {serviceImpl});
    handler.start();
    return handler.handle(new NexusTask(task, null, null), metricsScope);
  }

  private static PollNexusTaskQueueResponse.Builder startTask(Payload input) {
    return PollNexusTaskQueueResponse.newBuilder()
        .setRequest(
            Request.newBuilder()
                .setEndpoint(ENDPOINT)
                .setStartOperation(
                    StartOperationRequest.newBuilder()
                        .setService(SERVICE)
                        .setOperation(OPERATION)
                        .setPayload(input)));
  }

  private static String signature(NexusSerializationContext context) {
    return context.getEndpoint() + ":" + context.getService() + ":" + context.getOperation();
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class EchoServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return io.nexusrpc.handler.OperationHandler.sync(
          (ctx, details, name) -> "Hello, " + name + "!");
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class FailingServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return io.nexusrpc.handler.OperationHandler.sync(
          (ctx, details, name) -> {
            // The cause carries details so the failure conversion actually reaches the codec. A
            // failure with no details and no encoded attributes converts without touching it, and
            // an assertion on the codec would then prove nothing.
            throw OperationException.failed(
                ApplicationFailure.newNonRetryableFailure(
                    name, "ContextFailure", "failure-detail"));
          });
    }
  }

  /**
   * Stamps the serialization context it was given onto every payload it encodes, and records each
   * context it is handed so a test can assert which contexts were used and in what order.
   */
  private static class SigningCodec implements PayloadCodec {
    static final String SIGNATURE_KEY = "ser-ctx-signature";

    private final List<SerializationContext> seen;
    private final SerializationContext context;

    SigningCodec() {
      this(Collections.synchronizedList(new ArrayList<>()), null);
    }

    private SigningCodec(List<SerializationContext> seen, SerializationContext context) {
      this.seen = seen;
      this.context = context;
    }

    List<SerializationContext> contexts() {
      synchronized (seen) {
        return new ArrayList<>(seen);
      }
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
      List<Payload> encoded = new ArrayList<>(payloads.size());
      for (Payload payload : payloads) {
        encoded.add(
            Payload.newBuilder(payload)
                .putMetadata(
                    SIGNATURE_KEY,
                    ByteString.copyFromUtf8(signature((NexusSerializationContext) context)))
                .build());
      }
      return encoded;
    }

    @Override
    @Nonnull
    public List<Payload> decode(@Nonnull List<Payload> payloads) {
      seen.add(context);
      if (!(context instanceof NexusSerializationContext)) {
        return payloads;
      }
      String expected = signature((NexusSerializationContext) context);
      List<Payload> decoded = new ArrayList<>(payloads.size());
      for (Payload payload : payloads) {
        ByteString actual = payload.getMetadataMap().get(SIGNATURE_KEY);
        // Payloads encoded without a context stay readable, as the contract requires.
        if (actual != null) {
          Assert.assertEquals(
              "payload should be decoded under the context it was encoded with",
              expected,
              actual.toStringUtf8());
          decoded.add(Payload.newBuilder(payload).removeMetadata(SIGNATURE_KEY).build());
        } else {
          decoded.add(payload);
        }
      }
      return decoded;
    }
  }
}
