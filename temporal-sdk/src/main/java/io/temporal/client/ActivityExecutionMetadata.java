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
 * ActivityClient#listExecutions}.
 */
@Experimental
public class ActivityExecutionMetadata {

  private final @Nullable ActivityExecutionListInfo rawListInfo;
  private final String activityId;
  private final @Nullable String activityRunId;
  private final String activityType;
  private final @Nullable Instant closeTime;
  private final @Nullable Duration executionDuration;
  private final Instant scheduledTime;
  private final ActivityExecutionStatus status;
  private final String taskQueue;
  private final SearchAttributes searchAttributes;

  ActivityExecutionMetadata(
      @Nullable ActivityExecutionListInfo rawListInfo,
      String activityId,
      @Nullable String activityRunId,
      String activityType,
      @Nullable Instant closeTime,
      @Nullable Duration executionDuration,
      Instant scheduledTime,
      ActivityExecutionStatus status,
      String taskQueue,
      SearchAttributes searchAttributes) {
    this.rawListInfo = rawListInfo;
    this.activityId = activityId;
    this.activityRunId = activityRunId;
    this.activityType = activityType;
    this.closeTime = closeTime;
    this.executionDuration = executionDuration;
    this.scheduledTime = scheduledTime;
    this.status = status;
    this.taskQueue = taskQueue;
    this.searchAttributes = searchAttributes;
  }

  public static ActivityExecutionMetadata fromListInfo(ActivityExecutionListInfo info) {
    String runId = info.getRunId();
    return new ActivityExecutionMetadata(
        info,
        info.getActivityId(),
        runId.isEmpty() ? null : runId,
        info.getActivityType().getName(),
        info.hasCloseTime() ? ProtobufTimeUtils.toJavaInstant(info.getCloseTime()) : null,
        info.hasExecutionDuration()
            ? ProtobufTimeUtils.toJavaDuration(info.getExecutionDuration())
            : null,
        ProtobufTimeUtils.toJavaInstant(info.getScheduleTime()),
        info.getStatus(),
        info.getTaskQueue(),
        SearchAttributesUtil.decodeTyped(info.getSearchAttributes()));
  }

  /**
   * The raw protobuf list info from the server. Only present when this instance was created via
   * {@link #fromListInfo}; {@code null} for subclasses like {@link ActivityExecutionDescription}.
   */
  @Nullable
  public ActivityExecutionListInfo getRawListInfo() {
    return rawListInfo;
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

  /** Search attributes attached to this activity execution. */
  @Nonnull
  public SearchAttributes getSearchAttributes() {
    return searchAttributes;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ActivityExecutionMetadata that = (ActivityExecutionMetadata) o;
    return Objects.equals(activityId, that.activityId)
        && Objects.equals(activityRunId, that.activityRunId)
        && Objects.equals(activityType, that.activityType)
        && Objects.equals(closeTime, that.closeTime)
        && Objects.equals(executionDuration, that.executionDuration)
        && Objects.equals(scheduledTime, that.scheduledTime)
        && status == that.status
        && Objects.equals(taskQueue, that.taskQueue)
        && Objects.equals(searchAttributes, that.searchAttributes);
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
        status,
        taskQueue,
        searchAttributes);
  }

  @Override
  public String toString() {
    return "ActivityExecutionMetadata{"
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
        + "', searchAttributes="
        + searchAttributes
        + '}';
  }
}
