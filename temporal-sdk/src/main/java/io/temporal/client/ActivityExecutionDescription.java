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
  private final ActivityExecutionInfo info;
  private final DataConverter dataConverter;
  private final String namespace;

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
    this.info = response.getInfo();
    this.dataConverter = dataConverter;
    this.namespace = namespace;
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
    return info;
  }

  /** Current attempt number (starts at 1). */
  public int getAttempt() {
    return info.getAttempt();
  }

  /**
   * Reason that was provided when cancellation was requested. {@code null} if not cancelled or no
   * reason was given.
   */
  @Nullable
  public String getCanceledReason() {
    String r = info.getCanceledReason();
    return r.isEmpty() ? null : r;
  }

  /** Current or next retry interval. {@code null} if no retries are configured or allowed. */
  @Nullable
  public Duration getCurrentRetryInterval() {
    return info.hasCurrentRetryInterval()
        ? ProtobufTimeUtils.toJavaDuration(info.getCurrentRetryInterval())
        : null;
  }

  /** When the activity will time out (scheduled time + scheduleToCloseTimeout). */
  @Nullable
  public Instant getExpirationTime() {
    return info.hasExpirationTime()
        ? ProtobufTimeUtils.toJavaInstant(info.getExpirationTime())
        : null;
  }

  /** Maximum allowed time between heartbeats. */
  @Nullable
  public Duration getHeartbeatTimeout() {
    return info.hasHeartbeatTimeout()
        ? ProtobufTimeUtils.toJavaDuration(info.getHeartbeatTimeout())
        : null;
  }

  /** Time the last attempt completed (succeeded or failed). */
  @Nullable
  public Instant getLastAttemptCompleteTime() {
    return info.hasLastAttemptCompleteTime()
        ? ProtobufTimeUtils.toJavaInstant(info.getLastAttemptCompleteTime())
        : null;
  }

  /** Time the last heartbeat was recorded. */
  @Nullable
  public Instant getLastHeartbeatTime() {
    return info.hasLastHeartbeatTime()
        ? ProtobufTimeUtils.toJavaInstant(info.getLastHeartbeatTime())
        : null;
  }

  /** Time the last attempt was started. */
  @Nullable
  public Instant getLastStartedTime() {
    return info.hasLastStartedTime()
        ? ProtobufTimeUtils.toJavaInstant(info.getLastStartedTime())
        : null;
  }

  /**
   * Time the first activity task was made available for dispatch. Computed as {@code schedule_time
   * + start_delay}; equals {@code schedule_time} when no start delay is set.
   */
  @Nullable
  public Instant getExecutionTime() {
    return info.hasExecutionTime()
        ? ProtobufTimeUtils.toJavaInstant(info.getExecutionTime())
        : null;
  }

  /**
   * Delay before the first activity task is made available for dispatch. Not applied to retry
   * attempts. {@code null} if no start delay is set.
   */
  @Nullable
  public Duration getStartDelay() {
    return info.hasStartDelay() ? ProtobufTimeUtils.toJavaDuration(info.getStartDelay()) : null;
  }

  /**
   * Whether a failure from a failed attempt is present. {@code false} when the activity has no
   * failed attempt, and also when the description was requested without {@link
   * DescribeActivityOptions.Builder#setIncludeLastFailure(boolean)}.
   */
  public boolean hasLastFailure() {
    return info.hasLastFailure();
  }

  /** Failure details from the last failed attempt. {@code null} if no failure has occurred. */
  @Nullable
  public Exception getLastFailure() {
    return info.hasLastFailure() ? dataConverter.failureToException(info.getLastFailure()) : null;
  }

  /** Identity of the worker that last processed this activity. */
  @Nullable
  public String getLastWorkerIdentity() {
    String w = info.getLastWorkerIdentity();
    return w.isEmpty() ? null : w;
  }

  /** Time when the next retry attempt will be scheduled. */
  @Nullable
  public Instant getNextAttemptScheduleTime() {
    return info.hasNextAttemptScheduleTime()
        ? ProtobufTimeUtils.toJavaInstant(info.getNextAttemptScheduleTime())
        : null;
  }

  /** Retry policy for this activity. */
  @Nullable
  public RetryOptions getRetryOptions() {
    return info.hasRetryPolicy() ? RetryOptionsUtils.toRetryOptions(info.getRetryPolicy()) : null;
  }

  /**
   * Detailed run state (e.g. scheduled, started, backing off). Only meaningful when {@link
   * #getStatus()} is {@link ActivityExecutionStatus#ACTIVITY_EXECUTION_STATUS_RUNNING}.
   */
  @Nonnull
  public PendingActivityState getRunState() {
    return info.getRunState();
  }

  /** Total time the caller is willing to wait for the activity to complete, including retries. */
  @Nullable
  public Duration getScheduleToCloseTimeout() {
    return info.hasScheduleToCloseTimeout()
        ? ProtobufTimeUtils.toJavaDuration(info.getScheduleToCloseTimeout())
        : null;
  }

  /** Maximum time the task may wait in the task queue. */
  @Nullable
  public Duration getScheduleToStartTimeout() {
    return info.hasScheduleToStartTimeout()
        ? ProtobufTimeUtils.toJavaDuration(info.getScheduleToStartTimeout())
        : null;
  }

  /** Maximum time for a single attempt. */
  @Nullable
  public Duration getStartToCloseTimeout() {
    return info.hasStartToCloseTimeout()
        ? ProtobufTimeUtils.toJavaDuration(info.getStartToCloseTimeout())
        : null;
  }

  /**
   * Whether heartbeat details were recorded for the last attempt. {@code false} when the activity
   * recorded none, and also when the description was requested without {@link
   * DescribeActivityOptions.Builder#setIncludeHeartbeatDetails(boolean)}.
   */
  public boolean hasHeartbeatDetails() {
    return info.hasHeartbeatDetails();
  }

  /**
   * Deserializes the last heartbeat details into the given type. Returns {@link Optional#empty()}
   * if no heartbeat details are present.
   *
   * @param valueType the class to deserialize the heartbeat details into
   */
  public <V> Optional<V> getHeartbeatDetails(Class<V> valueType) {
    return getHeartbeatDetails(valueType, valueType);
  }

  /**
   * Deserializes the last heartbeat details into the given generic type. Returns {@link
   * Optional#empty()} if no heartbeat details are present.
   *
   * @param valueType the class to deserialize the heartbeat details into
   * @param genericType the generic type for deserialization; may equal {@code valueType}
   */
  public <V> Optional<V> getHeartbeatDetails(Class<V> valueType, Type genericType) {
    if (!info.hasHeartbeatDetails()) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        dataConverter.fromPayloads(
            0, Optional.of(info.getHeartbeatDetails()), valueType, genericType));
  }

  /**
   * Whether the activity's input is present. {@code false} unless the description was requested
   * with {@link DescribeActivityOptions.Builder#setIncludeInput(boolean)}.
   */
  public boolean hasInput() {
    return response.hasInput();
  }

  /**
   * The number of input arguments the activity was started with. {@code 0} if no input is present
   * (the activity took no arguments, or {@code includeInput} was false).
   */
  public int getInputCount() {
    return response.hasInput() ? response.getInput().getPayloadsCount() : 0;
  }

  /**
   * Deserializes the activity's first input argument. Returns {@link Optional#empty()} if no input
   * is present (the activity took no arguments, or {@code includeInput} was false).
   *
   * <p>For a multi-argument activity this returns only the first argument; use {@link
   * #getInput(int, Class)} to read the rest, and {@link #getInputCount()} for how many there are.
   *
   * @param valueType the class to deserialize the input into
   */
  public <V> Optional<V> getInput(Class<V> valueType) {
    return getInput(0, valueType, valueType);
  }

  /**
   * Deserializes the activity's first input argument into the given generic type. Returns {@link
   * Optional#empty()} if no input is present.
   *
   * @param valueType the class to deserialize the input into
   * @param genericType the generic type for deserialization; may equal {@code valueType}
   */
  public <V> Optional<V> getInput(Class<V> valueType, Type genericType) {
    return getInput(0, valueType, genericType);
  }

  /**
   * Deserializes the activity's input argument at the given position. Returns {@link
   * Optional#empty()} if no input is present or {@code index} is past the last argument.
   *
   * @param index zero-based position of the argument, in declaration order
   * @param valueType the class to deserialize the argument into
   */
  public <V> Optional<V> getInput(int index, Class<V> valueType) {
    return getInput(index, valueType, valueType);
  }

  /**
   * Deserializes the activity's input argument at the given position into the given generic type.
   * Returns {@link Optional#empty()} if no input is present or {@code index} is past the last
   * argument.
   *
   * @param index zero-based position of the argument, in declaration order
   * @param valueType the class to deserialize the argument into
   * @param genericType the generic type for deserialization; may equal {@code valueType}
   */
  public <V> Optional<V> getInput(int index, Class<V> valueType, Type genericType) {
    if (index < 0 || index >= getInputCount()) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        dataConverter.fromPayloads(
            index, Optional.of(response.getInput()), valueType, genericType));
  }

  /**
   * Whether the activity closed with a successful result. {@code false} while the activity is still
   * running, when it closed with a failure, or when the description was requested without {@link
   * DescribeActivityOptions.Builder#setIncludeOutcome(boolean)}.
   */
  public boolean hasResult() {
    return response.hasOutcome() && response.getOutcome().hasResult();
  }

  /**
   * Deserializes the activity's success result. Returns {@link Optional#empty()} if no result is
   * present (activity still running, closed with a failure, or {@code includeOutcome} was false).
   *
   * @param valueType the class to deserialize the result into
   */
  public <V> Optional<V> getResult(Class<V> valueType) {
    return getResult(valueType, valueType);
  }

  /**
   * Deserializes the activity's success result into the given generic type. Returns {@link
   * Optional#empty()} if no result is present.
   *
   * @param valueType the class to deserialize the result into
   * @param genericType the generic type for deserialization; may equal {@code valueType}
   */
  public <V> Optional<V> getResult(Class<V> valueType, Type genericType) {
    if (!hasResult()) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        dataConverter.fromPayloads(
            0, Optional.of(response.getOutcome().getResult()), valueType, genericType));
  }

  /**
   * The failure the activity closed with, as an exception. {@code null} if the activity did not
   * close with a failure or if {@code includeOutcome} was false on the describe call.
   *
   * <p>This is the terminal outcome; {@link #getLastFailure()} is the failure of the most recent
   * attempt, which may be set while the activity is still retrying.
   */
  @Nullable
  public Exception getFailure() {
    if (!response.hasOutcome() || !response.getOutcome().hasFailure()) {
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
    if (!info.hasLastDeploymentVersion()) {
      return null;
    }
    io.temporal.api.deployment.v1.WorkerDeploymentVersion proto = info.getLastDeploymentVersion();
    return new WorkerDeploymentVersion(proto.getDeploymentName(), proto.getBuildId());
  }

  /** Priority hint for this activity. {@code null} if not set. */
  @Nullable
  public Priority getPriority() {
    if (!info.hasPriority()) {
      return null;
    }
    return ProtoConverters.fromProto(info.getPriority());
  }

  /**
   * Fixed summary set when the activity was started. Decoded from UserMetadata on each call; cache
   * the result if called multiple times.
   */
  @Nullable
  public String getStaticSummary() {
    if (!info.hasUserMetadata() || !info.getUserMetadata().hasSummary()) {
      return null;
    }
    return dataConverter
        .withContext(
            new ActivitySerializationContext(
                namespace, null, null, getActivityType(), getTaskQueue(), false))
        .fromPayload(info.getUserMetadata().getSummary(), String.class, String.class);
  }

  /**
   * Fixed details set when the activity was started. Decoded from UserMetadata on each call; cache
   * the result if called multiple times.
   */
  @Nullable
  public String getStaticDetails() {
    if (!info.hasUserMetadata() || !info.getUserMetadata().hasDetails()) {
      return null;
    }
    return dataConverter
        .withContext(
            new ActivitySerializationContext(
                namespace, null, null, getActivityType(), getTaskQueue(), false))
        .fromPayload(info.getUserMetadata().getDetails(), String.class, String.class);
  }
}
