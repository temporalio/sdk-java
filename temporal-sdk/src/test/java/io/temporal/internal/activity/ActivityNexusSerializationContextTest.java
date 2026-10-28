package io.temporal.internal.activity;

import static io.temporal.internal.nexus.ContextRecordingCodec.contextOf;
import static io.temporal.internal.nexus.ContextRecordingCodec.stamp;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.nexus.v1.PropagatedSerializationContext;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.nexus.ContextRecordingCodec;
import io.temporal.internal.worker.ActivityTask;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.SerializationContext;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.SlotPermit;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests that a standalone activity started by a Nexus operation handler encodes its result,
 * failure and heartbeat details with the handler's Nexus context, as propagated by the server,
 * while its input keeps the activity context.
 */
public class ActivityNexusSerializationContextTest {

  private static final String NAMESPACE = "test-namespace";
  private static final String TASK_QUEUE = "tq";
  private static final NexusSerializationContext NEXUS_CONTEXT =
      new NexusSerializationContext("endpoint", "service", "operation");
  private static final PropagatedSerializationContext PROPAGATED =
      PropagatedSerializationContext.newBuilder()
          .setEndpoint("endpoint")
          .setService("service")
          .setOperation("operation")
          .build();
  private static final String NEXUS = ContextRecordingCodec.describe(NEXUS_CONTEXT);
  private static final String ACTIVITY = "activity:Echo";

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod(name = "Echo")
    String echo(String input);
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public String echo(String input) {
      if ("fail".equals(input)) {
        throw ApplicationFailure.newNonRetryableFailure("failed", "TestFailure", "detail");
      }
      return input;
    }
  }

  private ContextRecordingCodec codec;
  private ActivityTaskHandlerImpl handler;

  @Before
  public void setUp() {
    codec = new ContextRecordingCodec();
    ActivityExecutionContextFactory contextFactory = mock(ActivityExecutionContextFactory.class);
    when(contextFactory.createContext(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              InternalActivityExecutionContext context =
                  mock(InternalActivityExecutionContext.class);
              when(context.getInfo()).thenReturn(invocation.getArgument(0));
              when(context.getLastHeartbeatValue()).thenReturn("last-heartbeat");
              return context;
            });
    handler =
        new ActivityTaskHandlerImpl(
            NAMESPACE,
            TASK_QUEUE,
            codec.dataConverter(),
            contextFactory,
            new WorkerInterceptor[0],
            Collections.emptyList());
    handler.registerActivityImplementations(new Object[] {new TestActivityImpl()});
  }

  @Test
  public void propagatedContextIsUsedOnlyForResult() {
    ActivityTaskHandler.Result result = handle("hello", PROPAGATED);

    Assert.assertEquals(Collections.singletonList(ACTIVITY), codec.decodeContexts());
    Assert.assertEquals(NEXUS, contextOf(result.getTaskCompleted().getResult().getPayloads(0)));
  }

  @Test
  public void propagatedContextIsUsedForFailureAndHeartbeatDetails() {
    ActivityTaskHandler.Result result = handle("fail", PROPAGATED);

    Assert.assertEquals(
        NEXUS,
        contextOf(
            result
                .getTaskFailed()
                .getTaskFailedRequest()
                .getFailure()
                .getApplicationFailureInfo()
                .getDetails()
                .getPayloads(0)));
    Assert.assertEquals(
        NEXUS,
        contextOf(
            result
                .getTaskFailed()
                .getTaskFailedRequest()
                .getLastHeartbeatDetails()
                .getPayloads(0)));
  }

  @Test
  public void withoutPropagatedContextActivityContextIsUsed() {
    ActivityTaskHandler.Result result = handle("hello", null);

    Assert.assertEquals(Collections.singletonList(ACTIVITY), codec.decodeContexts());
    Assert.assertEquals(ACTIVITY, contextOf(result.getTaskCompleted().getResult().getPayloads(0)));
  }

  @Test
  public void propagatedContextIsUsedForHeartbeats() {
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(service.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenReturn(RecordActivityTaskHeartbeatResponse.getDefaultInstance());
    ActivityInfoInternal info = mock(ActivityInfoInternal.class);
    when(info.getTaskToken()).thenReturn(new byte[] {1});
    when(info.getActivityType()).thenReturn("Echo");
    when(info.getHeartbeatTimeout()).thenReturn(Duration.ZERO);
    when(info.getPropagatedNexusSerializationContext()).thenReturn(PROPAGATED);
    when(info.getHeartbeatDetails())
        .thenReturn(
            Optional.of(
                Payloads.newBuilder()
                    .addPayloads(
                        stamp(
                            DefaultDataConverter.STANDARD_INSTANCE.toPayload("previous").get(),
                            NEXUS_CONTEXT))
                    .build()));
    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    try {
      HeartbeatContextImpl heartbeatContext =
          new HeartbeatContextImpl(
              service,
              NAMESPACE,
              info,
              codec.dataConverter(),
              heartbeatExecutor,
              new NoopScope(),
              "test-identity",
              Duration.ofSeconds(60),
              Duration.ofSeconds(30),
              null);

      // The previous attempt's details are decoded, and new details encoded, with the Nexus
      // context, because the server puts them into the timeout failure returned to the caller.
      Assert.assertEquals(
          Optional.of("previous"),
          heartbeatContext.getHeartbeatDetails(String.class, String.class));
      Assert.assertEquals(Collections.singletonList(NEXUS), codec.decodeContexts());
      heartbeatContext.heartbeat("details");
      ArgumentCaptor<RecordActivityTaskHeartbeatRequest> request =
          ArgumentCaptor.forClass(RecordActivityTaskHeartbeatRequest.class);
      verify(blockingStub).recordActivityTaskHeartbeat(request.capture());
      Assert.assertEquals(NEXUS, contextOf(request.getValue().getDetails().getPayloads(0)));
    } finally {
      heartbeatExecutor.shutdownNow();
    }
  }

  @Test
  public void propagatedContextIsUsedForLocalManualCompletion() {
    WorkflowClient client = mock(WorkflowClient.class);
    when(client.getWorkflowServiceStubs()).thenReturn(mock(WorkflowServiceStubs.class));
    ActivityInfoInternal info = mock(ActivityInfoInternal.class);
    when(info.getNamespace()).thenReturn(NAMESPACE);
    when(info.getActivityType()).thenReturn("Echo");
    when(info.getTaskToken()).thenReturn(new byte[] {1});
    when(info.getPropagatedNexusSerializationContext()).thenReturn(PROPAGATED);
    ManualActivityCompletionClientFactory completionClientFactory =
        mock(ManualActivityCompletionClientFactory.class);
    ActivityExecutionContextImpl context =
        new ActivityExecutionContextImpl(
            client,
            NAMESPACE,
            new Object(),
            info,
            codec.dataConverter(),
            mock(ScheduledExecutorService.class),
            completionClientFactory,
            () -> {},
            new NoopScope(),
            "test-identity",
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            () -> {},
            null);

    context.useLocalManualCompletion();

    // The manual completion client reports the result, failure and heartbeats, so it uses the
    // Nexus context too.
    verify(completionClientFactory).getClient(any(), any(), eq(NEXUS_CONTEXT), any());
  }

  private ActivityTaskHandler.Result handle(
      String input, PropagatedSerializationContext propagated) {
    return handler.handle(
        new ActivityTask(pollResponse(input, propagated), new SlotPermit(), () -> {}),
        new NoopScope(),
        false);
  }

  private static PollActivityTaskQueueResponse pollResponse(
      String input, PropagatedSerializationContext propagated) {
    // The input is encoded with the activity context whether or not a context was propagated.
    SerializationContext encodedWith =
        new ActivitySerializationContext(NAMESPACE, null, null, "Echo", TASK_QUEUE, false);
    PollActivityTaskQueueResponse.Builder response =
        PollActivityTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFromUtf8("token"))
            .setActivityId("activity-id")
            .setActivityType(ActivityType.newBuilder().setName("Echo"))
            .setScheduledTime(ProtobufTimeUtils.toProtoTimestamp(Instant.EPOCH))
            .setStartedTime(ProtobufTimeUtils.toProtoTimestamp(Instant.EPOCH))
            .setInput(
                Payloads.newBuilder()
                    .addPayloads(
                        stamp(
                            DefaultDataConverter.STANDARD_INSTANCE.toPayload(input).get(),
                            encodedWith)));
    if (propagated != null) {
      response.setPropagatedNexusSerializationContext(propagated);
    }
    return response.build();
  }
}
