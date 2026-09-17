package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import io.temporal.internal.common.ProtoConverters;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.RetryOptionsUtils;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The options an activity is running with, as resolved by the server. Returned by {@link
 * UntypedActivityHandle#updateOptions} and {@link UntypedActivityHandle#restoreOriginalOptions}.
 */
@Experimental
public final class ActivityExecutionOptions {

  private final @Nullable String taskQueue;
  private final @Nullable Duration scheduleToCloseTimeout;
  private final @Nullable Duration scheduleToStartTimeout;
  private final @Nullable Duration startToCloseTimeout;
  private final @Nullable Duration heartbeatTimeout;
  private final @Nullable RetryOptions retryOptions;
  private final @Nullable Priority priority;
  private final @Nullable Duration startDelay;

  /**
   * Converts the server's resolved activity options into this type. An option the server did not
   * report is left null.
   */
  public ActivityExecutionOptions(@Nonnull io.temporal.api.activity.v1.ActivityOptions proto) {
    this.taskQueue = proto.hasTaskQueue() ? proto.getTaskQueue().getName() : null;
    this.scheduleToCloseTimeout =
        proto.hasScheduleToCloseTimeout()
            ? ProtobufTimeUtils.toJavaDuration(proto.getScheduleToCloseTimeout())
            : null;
    this.scheduleToStartTimeout =
        proto.hasScheduleToStartTimeout()
            ? ProtobufTimeUtils.toJavaDuration(proto.getScheduleToStartTimeout())
            : null;
    this.startToCloseTimeout =
        proto.hasStartToCloseTimeout()
            ? ProtobufTimeUtils.toJavaDuration(proto.getStartToCloseTimeout())
            : null;
    this.heartbeatTimeout =
        proto.hasHeartbeatTimeout()
            ? ProtobufTimeUtils.toJavaDuration(proto.getHeartbeatTimeout())
            : null;
    this.retryOptions =
        proto.hasRetryPolicy() ? RetryOptionsUtils.toRetryOptions(proto.getRetryPolicy()) : null;
    this.priority = proto.hasPriority() ? ProtoConverters.fromProto(proto.getPriority()) : null;
    this.startDelay =
        proto.hasStartDelay() ? ProtobufTimeUtils.toJavaDuration(proto.getStartDelay()) : null;
  }

  @Nullable
  public String getTaskQueue() {
    return taskQueue;
  }

  @Nullable
  public Duration getScheduleToCloseTimeout() {
    return scheduleToCloseTimeout;
  }

  @Nullable
  public Duration getScheduleToStartTimeout() {
    return scheduleToStartTimeout;
  }

  @Nullable
  public Duration getStartToCloseTimeout() {
    return startToCloseTimeout;
  }

  @Nullable
  public Duration getHeartbeatTimeout() {
    return heartbeatTimeout;
  }

  @Nullable
  public RetryOptions getRetryOptions() {
    return retryOptions;
  }

  @Nullable
  public Priority getPriority() {
    return priority;
  }

  @Nullable
  public Duration getStartDelay() {
    return startDelay;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ActivityExecutionOptions that = (ActivityExecutionOptions) o;
    return Objects.equals(taskQueue, that.taskQueue)
        && Objects.equals(scheduleToCloseTimeout, that.scheduleToCloseTimeout)
        && Objects.equals(scheduleToStartTimeout, that.scheduleToStartTimeout)
        && Objects.equals(startToCloseTimeout, that.startToCloseTimeout)
        && Objects.equals(heartbeatTimeout, that.heartbeatTimeout)
        && Objects.equals(retryOptions, that.retryOptions)
        && Objects.equals(priority, that.priority)
        && Objects.equals(startDelay, that.startDelay);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        taskQueue,
        scheduleToCloseTimeout,
        scheduleToStartTimeout,
        startToCloseTimeout,
        heartbeatTimeout,
        retryOptions,
        priority,
        startDelay);
  }

  @Override
  public String toString() {
    return "ActivityExecutionOptions{"
        + "taskQueue='"
        + taskQueue
        + "', scheduleToCloseTimeout="
        + scheduleToCloseTimeout
        + ", scheduleToStartTimeout="
        + scheduleToStartTimeout
        + ", startToCloseTimeout="
        + startToCloseTimeout
        + ", heartbeatTimeout="
        + heartbeatTimeout
        + ", retryOptions="
        + retryOptions
        + ", priority="
        + priority
        + ", startDelay="
        + startDelay
        + '}';
  }
}
