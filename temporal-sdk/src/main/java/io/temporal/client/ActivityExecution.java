package io.temporal.client;

import io.temporal.api.activity.v1.ActivityExecutionListInfo;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.common.Experimental;
import io.temporal.common.SearchAttributes;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Information about a standalone activity execution returned by {@link
 * WorkflowClient#listActivities}.
 */
@Experimental
public class ActivityExecution {

  private final String activityId;
  private final @Nullable String activityRunId;
  private final String activityType;
  private final @Nullable Instant closeTime;
  private final @Nullable Duration executionDuration;
  private final Instant scheduledTime;
  private final long stateTransitionCount;
  private final ActivityExecutionStatus status;
  private final String taskQueue;
  private final SearchAttributes typedSearchAttributes;

  ActivityExecution(
      String activityId,
      @Nullable String activityRunId,
      String activityType,
      @Nullable Instant closeTime,
      @Nullable Duration executionDuration,
      Instant scheduledTime,
      long stateTransitionCount,
      ActivityExecutionStatus status,
      String taskQueue,
      SearchAttributes typedSearchAttributes) {
    this.activityId = activityId;
    this.activityRunId = activityRunId;
    this.activityType = activityType;
    this.closeTime = closeTime;
    this.executionDuration = executionDuration;
    this.scheduledTime = scheduledTime;
    this.stateTransitionCount = stateTransitionCount;
    this.status = status;
    this.taskQueue = taskQueue;
    this.typedSearchAttributes = typedSearchAttributes;
  }

  public static ActivityExecution fromListInfo(ActivityExecutionListInfo info) {
    String runId = info.getRunId();
    return new ActivityExecution(
        info.getActivityId(),
        runId.isEmpty() ? null : runId,
        info.getActivityType().getName(),
        info.hasCloseTime() ? ProtobufTimeUtils.toJavaInstant(info.getCloseTime()) : null,
        info.hasExecutionDuration()
            ? ProtobufTimeUtils.toJavaDuration(info.getExecutionDuration())
            : null,
        ProtobufTimeUtils.toJavaInstant(info.getScheduleTime()),
        info.getStateTransitionCount(),
        info.getStatus(),
        info.getTaskQueue(),
        SearchAttributesUtil.decodeTyped(info.getSearchAttributes()));
  }

  /** The user-assigned identifier for this activity. */
  @Nonnull
  public String getActivityId() {
    return activityId;
  }

  /**
   * The server-assigned run ID for this activity execution. May be {@code null} for older server
   * versions.
   */
  @Nullable
  public String getActivityRunId() {
    return activityRunId;
  }

  /** The activity type name. */
  @Nonnull
  public String getActivityType() {
    return activityType;
  }

  /**
   * Time when the activity transitioned to a terminal status. {@code null} if the activity is still
   * running.
   */
  @Nullable
  public Instant getCloseTime() {
    return closeTime;
  }

  /**
   * The difference between close time and scheduled time. {@code null} if the activity is not yet
   * closed.
   */
  @Nullable
  public Duration getExecutionDuration() {
    return executionDuration;
  }

  /** Time when the activity was originally scheduled. */
  @Nonnull
  public Instant getScheduledTime() {
    return scheduledTime;
  }

  /** Number of state transitions for this activity execution. */
  public long getStateTransitionCount() {
    return stateTransitionCount;
  }

  /** General status of the activity execution. */
  @Nonnull
  public ActivityExecutionStatus getStatus() {
    return status;
  }

  /** The task queue this activity was scheduled on. */
  @Nonnull
  public String getTaskQueue() {
    return taskQueue;
  }

  /** Typed search attributes attached to this activity execution. */
  @Nonnull
  public SearchAttributes getTypedSearchAttributes() {
    return typedSearchAttributes;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ActivityExecution that = (ActivityExecution) o;
    return stateTransitionCount == that.stateTransitionCount
        && Objects.equals(activityId, that.activityId)
        && Objects.equals(activityRunId, that.activityRunId)
        && Objects.equals(activityType, that.activityType)
        && Objects.equals(closeTime, that.closeTime)
        && Objects.equals(executionDuration, that.executionDuration)
        && Objects.equals(scheduledTime, that.scheduledTime)
        && status == that.status
        && Objects.equals(taskQueue, that.taskQueue)
        && Objects.equals(typedSearchAttributes, that.typedSearchAttributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        activityId,
        activityRunId,
        activityType,
        closeTime,
        executionDuration,
        scheduledTime,
        stateTransitionCount,
        status,
        taskQueue,
        typedSearchAttributes);
  }

  @Override
  public String toString() {
    return "ActivityExecution{"
        + "activityId='"
        + activityId
        + "', activityRunId='"
        + activityRunId
        + "', activityType='"
        + activityType
        + "', status="
        + status
        + ", scheduledTime="
        + scheduledTime
        + ", closeTime="
        + closeTime
        + ", executionDuration="
        + executionDuration
        + ", taskQueue='"
        + taskQueue
        + "', stateTransitionCount="
        + stateTransitionCount
        + ", typedSearchAttributes="
        + typedSearchAttributes
        + '}';
  }
}
