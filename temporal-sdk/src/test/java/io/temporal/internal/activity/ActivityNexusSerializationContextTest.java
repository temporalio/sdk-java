package io.temporal.internal.activity;

import static io.temporal.internal.nexus.ContextRecordingCodec.contextOf;
import static io.temporal.internal.nexus.ContextRecordingCodec.stamp;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.nexus.v1.PropagatedSerializationContext;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.nexus.ContextRecordingCodec;
import io.temporal.internal.worker.ActivityTask;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.SerializationContext;
import io.temporal.worker.tuning.SlotPermit;
import java.time.Instant;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests that a standalone activity started by a Nexus operation handler encodes its result and
 * failure with the handler's Nexus context, as propagated by the server, while its input and
 * heartbeat details keep the activity context.
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
  public void propagatedContextIsUsedForFailureButNotHeartbeatDetails() {
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
        ACTIVITY,
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
  public void heartbeatContextKeepsActivityContext() {
    ActivityInfoImpl info =
        new ActivityInfoImpl(pollResponse("hello", PROPAGATED), NAMESPACE, TASK_QUEUE, false, null);

    Assert.assertEquals(
        ACTIVITY,
        ContextRecordingCodec.describe(
            ActivityInfoInternal.activitySerializationContext(NAMESPACE, info)));
    Assert.assertEquals(
        NEXUS_CONTEXT, ActivityInfoInternal.resultSerializationContext(NAMESPACE, info));
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
