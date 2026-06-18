package io.temporal.internal.client;

import static io.temporal.internal.common.RetryOptionsUtils.toRetryPolicy;

import com.google.protobuf.FieldMask;
import io.temporal.api.activity.v1.ActivityOptions;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityExecutionOptions;
import io.temporal.client.ResetActivityOptions;
import io.temporal.client.UnpauseActivityOptions;
import io.temporal.client.UntypedActivityHandle;
import io.temporal.client.UpdateActivityOptions;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.internal.common.ProtoConverters;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.RetryOptionsUtils;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * Implementation of {@link UntypedActivityHandle} that delegates lifecycle operations through the
 * interceptor chain.
 */
public final class ActivityHandleImpl implements UntypedActivityHandle {

  private final String activityId;
  private final @Nullable String activityRunId;
  private final ActivityClientCallsInterceptor clientCallsInterceptor;

  public ActivityHandleImpl(
      String activityId,
      @Nullable String activityRunId,
      ActivityClientCallsInterceptor clientCallsInterceptor) {
    this.activityId = activityId;
    this.activityRunId = activityRunId;
    this.clientCallsInterceptor = clientCallsInterceptor;
  }

  @Override
  public String getActivityId() {
    return activityId;
  }

  @Override
  public @Nullable String getActivityRunId() {
    return activityRunId;
  }

  @Override
  public <R> R getResult(Class<R> resultClass) {
    return getResult(resultClass, null);
  }

  @Override
  public <R> R getResult(Class<R> resultClass, @Nullable Type resultType) {
    try {
      return clientCallsInterceptor
          .getActivityResult(
              new ActivityClientCallsInterceptor.GetActivityResultInput<>(
                  activityId, activityRunId, resultClass, resultType))
          .getResult();
    } catch (TimeoutException e) {
      // unreachable: no-timeout input uses Long.MAX_VALUE deadline
      throw new RuntimeException(e);
    }
  }

  @Override
  public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass)
      throws TimeoutException {
    return getResult(timeout, unit, resultClass, null);
  }

  @Override
  public <R> R getResult(
      long timeout, TimeUnit unit, Class<R> resultClass, @Nullable Type resultType)
      throws TimeoutException {
    return clientCallsInterceptor
        .getActivityResult(
            new ActivityClientCallsInterceptor.GetActivityResultInput<>(
                activityId, activityRunId, resultClass, resultType, timeout, unit))
        .getResult();
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass) {
    return getResultAsync(resultClass, null);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, @Nullable Type resultType) {
    return getResultAsync(Long.MAX_VALUE, TimeUnit.MILLISECONDS, resultClass, resultType);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass) {
    return getResultAsync(timeout, unit, resultClass, null);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass, @Nullable Type resultType) {
    return clientCallsInterceptor
        .getActivityResultAsync(
            new ActivityClientCallsInterceptor.GetActivityResultInput<>(
                activityId, activityRunId, resultClass, resultType, timeout, unit))
        .thenApply(ActivityClientCallsInterceptor.GetActivityResultOutput::getResult);
  }

  @Override
  public ActivityExecutionDescription describe() {
    return clientCallsInterceptor
        .describeActivity(
            new ActivityClientCallsInterceptor.DescribeActivityInput(activityId, activityRunId))
        .getDescription();
  }

  @Override
  public void cancel() {
    cancel(null);
  }

  @Override
  public void cancel(@Nullable String reason) {
    clientCallsInterceptor.cancelActivity(
        new ActivityClientCallsInterceptor.CancelActivityInput(activityId, activityRunId, reason));
  }

  @Override
  public void terminate() {
    terminate(null);
  }

  @Override
  public void terminate(@Nullable String reason) {
    clientCallsInterceptor.terminateActivity(
        new ActivityClientCallsInterceptor.TerminateActivityInput(
            activityId, activityRunId, reason));
  }

  @Override
  public void pause() {
    pause(null);
  }

  @Override
  public void pause(@Nullable String reason) {
    clientCallsInterceptor.pauseActivity(
        new ActivityClientCallsInterceptor.PauseActivityInput(activityId, activityRunId, reason));
  }

  @Override
  public void unpause() {
    unpause(UnpauseActivityOptions.getDefaultInstance());
  }

  @Override
  public void unpause(UnpauseActivityOptions options) {
    clientCallsInterceptor.unpauseActivity(
        new ActivityClientCallsInterceptor.UnpauseActivityInput(
            activityId,
            activityRunId,
            options.getReason(),
            options.isResetAttempts(),
            options.isResetHeartbeat(),
            options.getJitter()));
  }

  @Override
  public void reset() {
    reset(ResetActivityOptions.getDefaultInstance());
  }

  @Override
  public void reset(ResetActivityOptions options) {
    clientCallsInterceptor.resetActivity(
        new ActivityClientCallsInterceptor.ResetActivityInput(
            activityId,
            activityRunId,
            options.isResetHeartbeat(),
            options.isKeepPaused(),
            options.getJitter(),
            options.isRestoreOriginalOptions()));
  }

  @Override
  public ActivityExecutionOptions updateOptions(UpdateActivityOptions options) {
    ActivityOptions.Builder activityOptions = ActivityOptions.newBuilder();
    List<String> maskPaths = new ArrayList<>();

    if (!options.isRestoreOriginal()) {
      if (options.getTaskQueue() != null) {
        activityOptions.setTaskQueue(
            TaskQueue.newBuilder().setName(options.getTaskQueue()).build());
        maskPaths.add("task_queue");
      }
      if (options.getScheduleToCloseTimeout() != null) {
        activityOptions.setScheduleToCloseTimeout(
            ProtobufTimeUtils.toProtoDuration(options.getScheduleToCloseTimeout()));
        maskPaths.add("schedule_to_close_timeout");
      }
      if (options.getScheduleToStartTimeout() != null) {
        activityOptions.setScheduleToStartTimeout(
            ProtobufTimeUtils.toProtoDuration(options.getScheduleToStartTimeout()));
        maskPaths.add("schedule_to_start_timeout");
      }
      if (options.getStartToCloseTimeout() != null) {
        activityOptions.setStartToCloseTimeout(
            ProtobufTimeUtils.toProtoDuration(options.getStartToCloseTimeout()));
        maskPaths.add("start_to_close_timeout");
      }
      if (options.getHeartbeatTimeout() != null) {
        activityOptions.setHeartbeatTimeout(
            ProtobufTimeUtils.toProtoDuration(options.getHeartbeatTimeout()));
        maskPaths.add("heartbeat_timeout");
      }
      if (options.getRetryOptions() != null) {
        activityOptions.setRetryPolicy(toRetryPolicy(options.getRetryOptions()));
        maskPaths.add("retry_policy");
      }
      if (options.getPriority() != null) {
        activityOptions.setPriority(ProtoConverters.toProto(options.getPriority()));
        maskPaths.add("priority");
      }
    }

    FieldMask updateMask = FieldMask.newBuilder().addAllPaths(maskPaths).build();

    ActivityClientCallsInterceptor.UpdateActivityOptionsOutput output =
        clientCallsInterceptor.updateActivityOptions(
            new ActivityClientCallsInterceptor.UpdateActivityOptionsInput(
                activityId,
                activityRunId,
                activityOptions.build(),
                updateMask,
                options.isRestoreOriginal()));

    return fromProto(output.getActivityOptions());
  }

  private static ActivityExecutionOptions fromProto(ActivityOptions proto) {
    return new ActivityExecutionOptions(
        proto.hasTaskQueue() ? proto.getTaskQueue().getName() : null,
        proto.hasScheduleToCloseTimeout()
            ? ProtobufTimeUtils.toJavaDuration(proto.getScheduleToCloseTimeout())
            : null,
        proto.hasScheduleToStartTimeout()
            ? ProtobufTimeUtils.toJavaDuration(proto.getScheduleToStartTimeout())
            : null,
        proto.hasStartToCloseTimeout()
            ? ProtobufTimeUtils.toJavaDuration(proto.getStartToCloseTimeout())
            : null,
        proto.hasHeartbeatTimeout()
            ? ProtobufTimeUtils.toJavaDuration(proto.getHeartbeatTimeout())
            : null,
        proto.hasRetryPolicy() ? RetryOptionsUtils.toRetryOptions(proto.getRetryPolicy()) : null,
        proto.hasPriority() ? ProtoConverters.fromProto(proto.getPriority()) : null);
  }
}
