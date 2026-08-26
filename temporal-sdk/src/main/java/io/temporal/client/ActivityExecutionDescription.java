package io.temporal.client;

import io.temporal.api.activity.v1.ActivityExecutionInfo;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.api.enums.v1.PendingActivityState;
import io.temporal.api.workflowservice.v1.DescribeActivityExecutionResponse;
import io.temporal.common.Experimental;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.internal.common.ProtoConverters;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.RetryOptionsUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.payload.context.ActivitySerializationContext;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
      DescribeActivityExecutionResponse response, DataConverter dataConverter, String namespace) {
    super(
        null,
        response.getInfo().getActivityId(),
        nullIfEmpty(response.getInfo().getRunId()),
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
    this.dataConverter =
        dataConverter.withContext(
            new ActivitySerializationContext(
                namespace, null, null, getActivityType(), getTaskQueue(), false));
  }

  private static @Nullable String nullIfEmpty(String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  /** Underlying proto response. Exposed while the standalone activity surface is experimental. */
  @Nonnull
  public DescribeActivityExecutionResponse getRawResponse() {
    return response;
  }

  /** The raw protobuf info returned by the server for this activity execution. */
  @Nonnull
  public ActivityExecutionInfo getRawInfo() {
    return response.getInfo();
  }

  /** Current attempt number (starts at 1). */
  public int getAttempt() {
    return response.getInfo().getAttempt();
  }

  /**
   * @return total number of heartbeats recorded across all attempts.
   */
  public long getTotalHeartbeatCount() {
    return response.getInfo().getTotalHeartbeatCount();
  }

  /**
   * Reason that was provided when cancellation was requested. {@code null} if not cancelled or no
   * reason was given.
   */
  @Nullable
  public String getCanceledReason() {
    String r = response.getInfo().getCanceledReason();
    return r.isEmpty() ? null : r;
  }

  /** Current or next retry interval. {@code null} if no retries are configured or allowed. */
  @Nullable
  public Duration getCurrentRetryInterval() {
    return response.getInfo().hasCurrentRetryInterval()
        ? ProtobufTimeUtils.toJavaDuration(response.getInfo().getCurrentRetryInterval())
        : null;
  }

  /** When the activity will time out (scheduled time + scheduleToCloseTimeout). */
  @Nullable
  public Instant getExpirationTime() {
    return response.getInfo().hasExpirationTime()
        ? ProtobufTimeUtils.toJavaInstant(response.getInfo().getExpirationTime())
        : null;
  }

  /** Maximum allowed time between heartbeats. */
  @Nullable
  public Duration getHeartbeatTimeout() {
    return response.getInfo().hasHeartbeatTimeout()
        ? ProtobufTimeUtils.toJavaDuration(response.getInfo().getHeartbeatTimeout())
        : null;
  }

  /** Time the last attempt completed (succeeded or failed). */
  @Nullable
  public Instant getLastAttemptCompleteTime() {
    return response.getInfo().hasLastAttemptCompleteTime()
        ? ProtobufTimeUtils.toJavaInstant(response.getInfo().getLastAttemptCompleteTime())
        : null;
  }

  /** Time the last heartbeat was recorded. */
  @Nullable
  public Instant getLastHeartbeatTime() {
    return response.getInfo().hasLastHeartbeatTime()
        ? ProtobufTimeUtils.toJavaInstant(response.getInfo().getLastHeartbeatTime())
        : null;
  }

  /** Time the last attempt was started. */
  @Nullable
  public Instant getLastStartedTime() {
    return response.getInfo().hasLastStartedTime()
        ? ProtobufTimeUtils.toJavaInstant(response.getInfo().getLastStartedTime())
        : null;
  }

  /**
   * Time the first activity task was made available for dispatch. Computed as {@code schedule_time
   * + start_delay}; equals {@code schedule_time} when no start delay is set.
   */
  @Nullable
  public Instant getExecutionTime() {
    return response.getInfo().hasExecutionTime()
        ? ProtobufTimeUtils.toJavaInstant(response.getInfo().getExecutionTime())
        : null;
  }

  /**
   * Delay before the first activity task is made available for dispatch. Not applied to retry
   * attempts. {@code null} if no start delay is set.
   */
  @Nullable
  public Duration getStartDelay() {
    return response.getInfo().hasStartDelay()
        ? ProtobufTimeUtils.toJavaDuration(response.getInfo().getStartDelay())
        : null;
  }

  /**
   * Whether a failure from a failed attempt is present. {@code false} when the activity has no
   * failed attempt, and also when the description was requested without {@link
   * DescribeActivityOptions.Builder#setIncludeLastFailure(boolean)}.
   */
  public boolean hasLastFailure() {
    return response.getInfo().hasLastFailure();
  }

  /** Failure details from the last failed attempt. {@code null} if no failure has occurred. */
  @Nullable
  public RuntimeException getLastFailure() {
    return response.getInfo().hasLastFailure()
        ? dataConverter.failureToException(response.getInfo().getLastFailure())
        : null;
  }

  /** Identity of the worker that last processed this activity. */
  @Nullable
  public String getLastWorkerIdentity() {
    String w = response.getInfo().getLastWorkerIdentity();
    return w.isEmpty() ? null : w;
  }

  /** Time when the next retry attempt will be scheduled. */
  @Nullable
  public Instant getNextAttemptScheduleTime() {
    return response.getInfo().hasNextAttemptScheduleTime()
        ? ProtobufTimeUtils.toJavaInstant(response.getInfo().getNextAttemptScheduleTime())
        : null;
  }

  /** Retry policy for this activity. */
  @Nullable
  public RetryOptions getRetryOptions() {
    return response.getInfo().hasRetryPolicy()
        ? RetryOptionsUtils.toRetryOptions(response.getInfo().getRetryPolicy())
        : null;
  }

  /**
   * Detailed run state (e.g. scheduled, started, backing off). Only meaningful when {@link
   * #getStatus()} is {@link ActivityExecutionStatus#ACTIVITY_EXECUTION_STATUS_RUNNING}.
   */
  @Nonnull
  public PendingActivityState getRunState() {
    return response.getInfo().getRunState();
  }

  /** Total time the caller is willing to wait for the activity to complete, including retries. */
  @Nullable
  public Duration getScheduleToCloseTimeout() {
    return response.getInfo().hasScheduleToCloseTimeout()
        ? ProtobufTimeUtils.toJavaDuration(response.getInfo().getScheduleToCloseTimeout())
        : null;
  }

  /** Maximum time the task may wait in the task queue. */
  @Nullable
  public Duration getScheduleToStartTimeout() {
    return response.getInfo().hasScheduleToStartTimeout()
        ? ProtobufTimeUtils.toJavaDuration(response.getInfo().getScheduleToStartTimeout())
        : null;
  }

  /** Maximum time for a single attempt. */
  @Nullable
  public Duration getStartToCloseTimeout() {
    return response.getInfo().hasStartToCloseTimeout()
        ? ProtobufTimeUtils.toJavaDuration(response.getInfo().getStartToCloseTimeout())
        : null;
  }

  /**
   * Whether heartbeat details were recorded for the last attempt. {@code false} when the activity
   * recorded none, and also when the description was requested without {@link
   * DescribeActivityOptions.Builder#setIncludeHeartbeatDetails(boolean)}.
   */
  public boolean hasHeartbeatDetails() {
    return response.getInfo().hasHeartbeatDetails();
  }

  /**
   * The details recorded by the last heartbeat, as lazily-decoded values. Empty (size 0) when no
   * heartbeat details are present, either because none were recorded or because the description was
   * requested without {@link DescribeActivityOptions.Builder#setIncludeHeartbeatDetails(boolean)}.
   */
  public EncodedValues getHeartbeatDetails() {
    return new EncodedValues(Optional.of(response.getInfo().getHeartbeatDetails()), dataConverter);
  }

  /**
   * Whether the activity's input is present. {@code false} unless the description was requested
   * with {@link DescribeActivityOptions.Builder#setIncludeInput(boolean)}.
   */
  public boolean hasInput() {
    return response.hasInput();
  }

  /**
   * The activity's input arguments, as lazily-decoded values, one per argument. Empty (size 0) when
   * no input is present, either because the activity took no arguments or because the description
   * was requested without {@link DescribeActivityOptions.Builder#setIncludeInput(boolean)}.
   */
  public EncodedValues getInput() {
    return new EncodedValues(Optional.of(response.getInput()), dataConverter);
  }

  /**
   * Whether the activity closed with a successful result. {@code false} while the activity is still
   * running, when it closed with a failure, or when the description was requested without {@link
   * DescribeActivityOptions.Builder#setIncludeOutcome(boolean)}.
   */
  public boolean hasResult() {
    return response.getOutcome().hasResult();
  }

  /**
   * Deserializes the activity's success result. Returns {@link Optional#empty()} if no result is
   * present (activity still running, closed with a failure, or {@code includeOutcome} was false).
   *
   * @param valueType the class to deserialize the result into
   */
  public <V> Optional<V> getResult(Class<V> valueType) {
    return getResult(valueType, null);
  }

  /**
   * Deserializes the activity's success result into the given generic type. Returns {@link
   * Optional#empty()} if no result is present.
   *
   * @param valueType the class to deserialize the result into
   * @param genericType the generic type for deserialization; may equal {@code valueType}
   */
  public <V> Optional<V> getResult(Class<V> valueType, @Nullable Type genericType) {
    if (!hasResult()) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        dataConverter.fromPayloads(
            0,
            Optional.of(response.getOutcome().getResult()),
            valueType,
            genericType != null ? genericType : valueType));
  }

  /**
   * The failure the activity closed with, as an exception. {@code null} if the activity did not
   * close with a failure or if {@code includeOutcome} was false on the describe call.
   *
   * <p>This is the terminal outcome; {@link #getLastFailure()} is the failure of the most recent
   * attempt, which may be set while the activity is still retrying.
   */
  @Nullable
  public RuntimeException getOutcomeFailure() {
    if (!response.getOutcome().hasFailure()) {
      return null;
    }
    return dataConverter.failureToException(response.getOutcome().getFailure());
  }

  /**
   * The deployment version of the worker that last processed this activity. {@code null} if not
   * available.
   */
  @Nullable
  public WorkerDeploymentVersion getWorkerDeploymentVersion() {
    if (!response.getInfo().hasLastDeploymentVersion()) {
      return null;
    }
    io.temporal.api.deployment.v1.WorkerDeploymentVersion proto =
        response.getInfo().getLastDeploymentVersion();
    return new WorkerDeploymentVersion(proto.getDeploymentName(), proto.getBuildId());
  }

  /** Priority hint for this activity. {@code null} if not set. */
  @Nullable
  public Priority getPriority() {
    if (!response.getInfo().hasPriority()) {
      return null;
    }
    return ProtoConverters.fromProto(response.getInfo().getPriority());
  }

  /**
   * Fixed summary set when the activity was started. Decoded from UserMetadata on each call; cache
   * the result if called multiple times.
   */
  @Nullable
  public String getStaticSummary() {
    if (!response.getInfo().getUserMetadata().hasSummary()) {
      return null;
    }
    return dataConverter.fromPayload(
        response.getInfo().getUserMetadata().getSummary(), String.class, String.class);
  }

  /**
   * Fixed details set when the activity was started. Decoded from UserMetadata on each call; cache
   * the result if called multiple times.
   */
  @Nullable
  public String getStaticDetails() {
    if (!response.getInfo().getUserMetadata().hasDetails()) {
      return null;
    }
    return dataConverter.fromPayload(
        response.getInfo().getUserMetadata().getDetails(), String.class, String.class);
  }
}
