package io.temporal.internal.client;

import static io.temporal.internal.common.RetryOptionsUtils.toRetryPolicy;

import com.google.protobuf.FieldMask;
import io.temporal.api.activity.v1.ActivityOptions;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityExecutionOptions;
import io.temporal.client.ActivityOptionsKey;
import io.temporal.client.ActivityOptionsKeys;
import io.temporal.client.ActivityOptionsUpdate;
import io.temporal.client.DescribeActivityOptions;
import io.temporal.client.PauseActivityOptions;
import io.temporal.client.ResetActivityOptions;
import io.temporal.client.UnpauseActivityOptions;
import io.temporal.client.UntypedActivityHandle;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.internal.common.ProtoConverters;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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
    return describe(DescribeActivityOptions.getDefaultInstance());
  }

  @Override
  public ActivityExecutionDescription describe(DescribeActivityOptions options) {
    return clientCallsInterceptor
        .describeActivity(
            new ActivityClientCallsInterceptor.DescribeActivityInput(
                activityId, activityRunId, options))
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
    pause(PauseActivityOptions.getDefaultInstance());
  }

  @Override
  public void pause(PauseActivityOptions options) {
    clientCallsInterceptor.pauseActivity(
        new ActivityClientCallsInterceptor.PauseActivityInput(activityId, activityRunId, options));
  }

  @Override
  public void unpause() {
    unpause(UnpauseActivityOptions.getDefaultInstance());
  }

  @Override
  public void unpause(UnpauseActivityOptions options) {
    clientCallsInterceptor.unpauseActivity(
        new ActivityClientCallsInterceptor.UnpauseActivityInput(
            activityId, activityRunId, options));
  }

  @Override
  public void reset() {
    reset(ResetActivityOptions.getDefaultInstance());
  }

  @Override
  public void reset(ResetActivityOptions options) {
    clientCallsInterceptor.resetActivity(
        new ActivityClientCallsInterceptor.ResetActivityInput(activityId, activityRunId, options));
  }

  @Override
  public ActivityExecutionOptions updateOptions(ActivityOptionsUpdate<?>... updates) {
    ActivityOptions.Builder activityOptions = ActivityOptions.newBuilder();
    // For repeated keys, later values override previous ones.
    Map<String, ActivityOptionsUpdate<?>> byPath = new HashMap<>();
    for (ActivityOptionsUpdate<?> update : updates) {
      if (update != null) {
        byPath.put(update.getKey().getName(), update);
      }
    }

    // An update naming nothing would send an empty mask and silently change nothing. Fail here
    // rather than making a round trip that looks like it worked. Use restoreOriginalOptions() to
    // revert options instead.
    if (byPath.isEmpty()) {
      throw new IllegalArgumentException("updateOptions requires at least one option update");
    }

    for (ActivityOptionsUpdate<?> update : byPath.values()) {
      // An unset update names its path but leaves the field absent, which is how the server is
      // told to clear the option.
      update.getValue().ifPresent(value -> applyUpdate(activityOptions, update.getKey(), value));
    }

    FieldMask updateMask = FieldMask.newBuilder().addAllPaths(byPath.keySet()).build();

    ActivityClientCallsInterceptor.UpdateActivityOptionsOutput output =
        clientCallsInterceptor.updateActivityOptions(
            new ActivityClientCallsInterceptor.UpdateActivityOptionsInput(
                activityId, activityRunId, activityOptions.build(), updateMask, false));

    return output.getOptions();
  }

  /**
   * Writes one option's value onto the request. The cast is safe because every key is created by
   * {@link ActivityOptionsKeys} with the value type its path expects.
   */
  private static void applyUpdate(
      ActivityOptions.Builder options, ActivityOptionsKey<?> key, Object value) {
    switch (key.getName()) {
      case "task_queue.name":
        options.setTaskQueue(TaskQueue.newBuilder().setName((String) value).build());
        break;
      case "schedule_to_close_timeout":
        options.setScheduleToCloseTimeout(ProtobufTimeUtils.toProtoDuration((Duration) value));
        break;
      case "schedule_to_start_timeout":
        options.setScheduleToStartTimeout(ProtobufTimeUtils.toProtoDuration((Duration) value));
        break;
      case "start_to_close_timeout":
        options.setStartToCloseTimeout(ProtobufTimeUtils.toProtoDuration((Duration) value));
        break;
      case "heartbeat_timeout":
        options.setHeartbeatTimeout(ProtobufTimeUtils.toProtoDuration((Duration) value));
        break;
      case "start_delay":
        options.setStartDelay(ProtobufTimeUtils.toProtoDuration((Duration) value));
        break;
      case "retry_policy":
        options.setRetryPolicy(toRetryPolicy((RetryOptions) value));
        break;
      case "priority":
        options.setPriority(ProtoConverters.toProto((Priority) value));
        break;
      default:
        throw new IllegalArgumentException("Unknown activity option: " + key.getName());
    }
  }

  @Override
  public ActivityExecutionOptions restoreOriginalOptions() {
    ActivityClientCallsInterceptor.UpdateActivityOptionsOutput output =
        clientCallsInterceptor.updateActivityOptions(
            new ActivityClientCallsInterceptor.UpdateActivityOptionsInput(
                activityId,
                activityRunId,
                ActivityOptions.getDefaultInstance(),
                FieldMask.getDefaultInstance(),
                true));
    return output.getOptions();
  }
}
