package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * The resolved options of a standalone activity execution, returned by {@link
 * UntypedActivityHandle#updateOptions(UpdateActivityOptions)}.
 *
 * <p>Reflects the activity's options as the server resolved them after the update was applied.
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

  public ActivityExecutionOptions(
      @Nullable String taskQueue,
      @Nullable Duration scheduleToCloseTimeout,
      @Nullable Duration scheduleToStartTimeout,
      @Nullable Duration startToCloseTimeout,
      @Nullable Duration heartbeatTimeout,
      @Nullable RetryOptions retryOptions,
      @Nullable Priority priority) {
    this.taskQueue = taskQueue;
    this.scheduleToCloseTimeout = scheduleToCloseTimeout;
    this.scheduleToStartTimeout = scheduleToStartTimeout;
    this.startToCloseTimeout = startToCloseTimeout;
    this.heartbeatTimeout = heartbeatTimeout;
    this.retryOptions = retryOptions;
    this.priority = priority;
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
        && Objects.equals(priority, that.priority);
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
        priority);
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
        + '}';
  }
}
