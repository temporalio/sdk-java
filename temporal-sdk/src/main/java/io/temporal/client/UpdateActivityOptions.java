package io.temporal.client;

import com.google.common.base.Preconditions;
import io.temporal.common.Experimental;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Options for {@link UntypedActivityHandle#updateOptions(UpdateActivityOptions)}.
 *
 * <p>Only the fields that are explicitly set are sent to the server; a derived field mask ensures
 * that unset fields are left unchanged (a partial update).
 *
 * <p>{@link Builder#setRestoreOriginal(boolean)} is mutually exclusive with every other field: an
 * instance that sets {@code restoreOriginal} together with any other option is rejected by {@link
 * Builder#build()} before any request is sent.
 */
@Experimental
public final class UpdateActivityOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(UpdateActivityOptions options) {
    return new Builder(options);
  }

  public static final class Builder {
    private @Nullable String taskQueue;
    private @Nullable Duration scheduleToCloseTimeout;
    private @Nullable Duration scheduleToStartTimeout;
    private @Nullable Duration startToCloseTimeout;
    private @Nullable Duration heartbeatTimeout;
    private @Nullable RetryOptions retryOptions;
    private @Nullable Priority priority;
    private boolean restoreOriginal;

    private Builder() {}

    private Builder(UpdateActivityOptions options) {
      if (options == null) {
        return;
      }
      this.taskQueue = options.taskQueue;
      this.scheduleToCloseTimeout = options.scheduleToCloseTimeout;
      this.scheduleToStartTimeout = options.scheduleToStartTimeout;
      this.startToCloseTimeout = options.startToCloseTimeout;
      this.heartbeatTimeout = options.heartbeatTimeout;
      this.retryOptions = options.retryOptions;
      this.priority = options.priority;
      this.restoreOriginal = options.restoreOriginal;
    }

    /** New task queue for the activity. */
    public Builder setTaskQueue(@Nullable String taskQueue) {
      this.taskQueue = taskQueue;
      return this;
    }

    /** New schedule-to-close timeout. */
    public Builder setScheduleToCloseTimeout(@Nullable Duration scheduleToCloseTimeout) {
      this.scheduleToCloseTimeout = scheduleToCloseTimeout;
      return this;
    }

    /** New schedule-to-start timeout. */
    public Builder setScheduleToStartTimeout(@Nullable Duration scheduleToStartTimeout) {
      this.scheduleToStartTimeout = scheduleToStartTimeout;
      return this;
    }

    /** New start-to-close timeout. */
    public Builder setStartToCloseTimeout(@Nullable Duration startToCloseTimeout) {
      this.startToCloseTimeout = startToCloseTimeout;
      return this;
    }

    /** New heartbeat timeout. */
    public Builder setHeartbeatTimeout(@Nullable Duration heartbeatTimeout) {
      this.heartbeatTimeout = heartbeatTimeout;
      return this;
    }

    /** New retry policy. */
    public Builder setRetryOptions(@Nullable RetryOptions retryOptions) {
      this.retryOptions = retryOptions;
      return this;
    }

    /** New priority. */
    public Builder setPriority(@Nullable Priority priority) {
      this.priority = priority;
      return this;
    }

    /**
     * If set, the activity options are restored to the originals the activity was created with.
     * This flag cannot be combined with any other field.
     */
    public Builder setRestoreOriginal(boolean restoreOriginal) {
      this.restoreOriginal = restoreOriginal;
      return this;
    }

    public UpdateActivityOptions build() {
      if (restoreOriginal) {
        Preconditions.checkArgument(
            taskQueue == null
                && scheduleToCloseTimeout == null
                && scheduleToStartTimeout == null
                && startToCloseTimeout == null
                && heartbeatTimeout == null
                && retryOptions == null
                && priority == null,
            "restoreOriginal cannot be combined with any other option");
      } else {
        Preconditions.checkArgument(
            taskQueue != null
                || scheduleToCloseTimeout != null
                || scheduleToStartTimeout != null
                || startToCloseTimeout != null
                || heartbeatTimeout != null
                || retryOptions != null
                || priority != null,
            "At least one option must be set, or restoreOriginal must be used");
      }
      return new UpdateActivityOptions(this);
    }
  }

  private final @Nullable String taskQueue;
  private final @Nullable Duration scheduleToCloseTimeout;
  private final @Nullable Duration scheduleToStartTimeout;
  private final @Nullable Duration startToCloseTimeout;
  private final @Nullable Duration heartbeatTimeout;
  private final @Nullable RetryOptions retryOptions;
  private final @Nullable Priority priority;
  private final boolean restoreOriginal;

  private UpdateActivityOptions(Builder builder) {
    this.taskQueue = builder.taskQueue;
    this.scheduleToCloseTimeout = builder.scheduleToCloseTimeout;
    this.scheduleToStartTimeout = builder.scheduleToStartTimeout;
    this.startToCloseTimeout = builder.startToCloseTimeout;
    this.heartbeatTimeout = builder.heartbeatTimeout;
    this.retryOptions = builder.retryOptions;
    this.priority = builder.priority;
    this.restoreOriginal = builder.restoreOriginal;
  }

  public Builder toBuilder() {
    return new Builder(this);
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

  public boolean isRestoreOriginal() {
    return restoreOriginal;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    UpdateActivityOptions that = (UpdateActivityOptions) o;
    return restoreOriginal == that.restoreOriginal
        && Objects.equals(taskQueue, that.taskQueue)
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
        priority,
        restoreOriginal);
  }

  @Override
  public String toString() {
    return "UpdateActivityOptions{"
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
        + ", restoreOriginal="
        + restoreOriginal
        + '}';
  }
}
