package io.temporal.client;

import io.temporal.api.activity.v1.ActivityExecutionInfo;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.api.enums.v1.PendingActivityState;
import io.temporal.api.workflowservice.v1.DescribeActivityExecutionResponse;
import io.temporal.common.Experimental;
import io.temporal.common.RetryOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.RetryOptionsUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.payload.context.ActivitySerializationContext;
import java.time.Duration;
import java.time.Instant;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Detailed information about a standalone activity execution, returned by {@link
 * ActivityHandle#describe()}.
 */
@Experimental
public final class ActivityExecutionDescription extends ActivityExecutionMetadata {

  private final DescribeActivityExecutionResponse response;
  private final DataConverter dataConverter;

  public ActivityExecutionDescription(
      DescribeActivityExecutionResponse response, DataConverter dataConverter) {
    super(
        null,
        response.getInfo().getActivityId(),
        nullIfEmpty(response.getRunId()),
        response.getInfo().getActivityType().getName(),
        response.getInfo().hasCloseTime()
            ? ProtobufTimeUtils.toJavaInstant(response.getInfo().getCloseTime())
            : null,
        response.getInfo().hasExecutionDuration()
            ? ProtobufTimeUtils.toJavaDuration(response.getInfo().getExecutionDuration())
            : null,
        response.getInfo().hasScheduleTime()
            ? ProtobufTimeUtils.toJavaInstant(response.getInfo().getScheduleTime())
            : Instant.EPOCH,
        response.getInfo().getStatus(),
        response.getInfo().getTaskQueue(),
        SearchAttributesUtil.decodeTyped(response.getInfo().getSearchAttributes()));
    this.response = response;
    this.dataConverter = dataConverter;
  }

  private static @Nullable String nullIfEmpty(String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  private ActivityExecutionInfo info() {
    return response.getInfo();
  }

  /** Current attempt number (starts at 1). */
  public int getAttempt() {
    return info().getAttempt();
  }

  /**
   * Reason that was provided when cancellation was requested. {@code null} if not cancelled or no
   * reason was given.
   */
  @Nullable
  public String getCanceledReason() {
    String r = info().getCanceledReason();
    return r.isEmpty() ? null : r;
  }

  /**
   * Current or next retry interval. {@code null} if no retries are configured or allowed.
   *
   * @see ActivityExecutionInfoOrBuilder#getCurrentRetryInterval()
   */
  @Nullable
  public Duration getCurrentRetryInterval() {
    return info().hasCurrentRetryInterval()
        ? ProtobufTimeUtils.toJavaDuration(info().getCurrentRetryInterval())
        : null;
  }

  /** When the activity will time out (scheduled time + scheduleToCloseTimeout). */
  @Nullable
  public Instant getExpirationTime() {
    return info().hasExpirationTime()
        ? ProtobufTimeUtils.toJavaInstant(info().getExpirationTime())
        : null;
  }

  /** Maximum allowed time between heartbeats. */
  @Nullable
  public Duration getHeartbeatTimeout() {
    return info().hasHeartbeatTimeout()
        ? ProtobufTimeUtils.toJavaDuration(info().getHeartbeatTimeout())
        : null;
  }

  /** Time the last attempt completed (succeeded or failed). */
  @Nullable
  public Instant getLastAttemptCompleteTime() {
    return info().hasLastAttemptCompleteTime()
        ? ProtobufTimeUtils.toJavaInstant(info().getLastAttemptCompleteTime())
        : null;
  }

  /** Time the last heartbeat was recorded. */
  @Nullable
  public Instant getLastHeartbeatTime() {
    return info().hasLastHeartbeatTime()
        ? ProtobufTimeUtils.toJavaInstant(info().getLastHeartbeatTime())
        : null;
  }

  /** Time the last attempt was started. */
  @Nullable
  public Instant getLastStartedTime() {
    return info().hasLastStartedTime()
        ? ProtobufTimeUtils.toJavaInstant(info().getLastStartedTime())
        : null;
  }

  /** Identity of the worker that last processed this activity. */
  @Nullable
  public String getLastWorkerIdentity() {
    String w = info().getLastWorkerIdentity();
    return w.isEmpty() ? null : w;
  }

  /**
   * Long-poll token for the next describe call. {@code null} if the activity is complete.
   *
   * @see ActivityDescribeOptions#getLongPollToken()
   */
  @Nullable
  public byte[] getLongPollToken() {
    com.google.protobuf.ByteString bs = response.getLongPollToken();
    return (bs == null || bs.isEmpty()) ? null : bs.toByteArray();
  }

  /** Time when the next retry attempt will be scheduled. */
  @Nullable
  public Instant getNextAttemptScheduleTime() {
    return info().hasNextAttemptScheduleTime()
        ? ProtobufTimeUtils.toJavaInstant(info().getNextAttemptScheduleTime())
        : null;
  }

  /** Retry policy for this activity. */
  @Nullable
  public RetryOptions getRetryPolicy() {
    return info().hasRetryPolicy()
        ? RetryOptionsUtils.toRetryOptions(info().getRetryPolicy())
        : null;
  }

  /**
   * Detailed run state (e.g. scheduled, started, backing off). Only meaningful when {@link
   * #getStatus()} is {@link ActivityExecutionStatus#ACTIVITY_EXECUTION_STATUS_RUNNING}.
   */
  @Nonnull
  public PendingActivityState getRunState() {
    return info().getRunState();
  }

  /** Total time the caller is willing to wait for the activity to complete, including retries. */
  @Nullable
  public Duration getScheduleToCloseTimeout() {
    return info().hasScheduleToCloseTimeout()
        ? ProtobufTimeUtils.toJavaDuration(info().getScheduleToCloseTimeout())
        : null;
  }

  /** Maximum time the task may wait in the task queue. */
  @Nullable
  public Duration getScheduleToStartTimeout() {
    return info().hasScheduleToStartTimeout()
        ? ProtobufTimeUtils.toJavaDuration(info().getScheduleToStartTimeout())
        : null;
  }

  /** Maximum time for a single attempt. */
  @Nullable
  public Duration getStartToCloseTimeout() {
    return info().hasStartToCloseTimeout()
        ? ProtobufTimeUtils.toJavaDuration(info().getStartToCloseTimeout())
        : null;
  }

  /**
   * Fixed summary set when the activity was started. Decoded from UserMetadata on each call; cache
   * the result if called multiple times.
   */
  @Nullable
  public String getStaticSummary() {
    if (!info().hasUserMetadata() || !info().getUserMetadata().hasSummary()) {
      return null;
    }
    return dataConverter
        .withContext(
            new ActivitySerializationContext("", "", "", getActivityType(), getTaskQueue(), false))
        .fromPayload(info().getUserMetadata().getSummary(), String.class, String.class);
  }

  /**
   * Fixed details set when the activity was started. Decoded from UserMetadata on each call; cache
   * the result if called multiple times.
   */
  @Nullable
  public String getStaticDetails() {
    if (!info().hasUserMetadata() || !info().getUserMetadata().hasDetails()) {
      return null;
    }
    return dataConverter
        .withContext(
            new ActivitySerializationContext("", "", "", getActivityType(), getTaskQueue(), false))
        .fromPayload(info().getUserMetadata().getDetails(), String.class, String.class);
  }

  /** Returns the raw response from the Temporal service. */
  @Nonnull
  public DescribeActivityExecutionResponse getRawDescription() {
    return response;
  }
}
