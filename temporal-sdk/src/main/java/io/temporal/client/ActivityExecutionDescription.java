package io.temporal.client;

import io.temporal.api.activity.v1.ActivityExecutionInfo;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.api.enums.v1.PendingActivityState;
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

  private final ActivityExecutionInfo info;
  private final DataConverter dataConverter;
  private final String namespace;
  private final @Nullable byte[] longPollToken;

  public ActivityExecutionDescription(
      ActivityExecutionInfo info,
      DataConverter dataConverter,
      String namespace,
      @Nullable byte[] longPollToken) {
    super(
        null,
        info.getActivityId(),
        nullIfEmpty(info.getRunId()),
        info.getActivityType().getName(),
        info.hasCloseTime() ? ProtobufTimeUtils.toJavaInstant(info.getCloseTime()) : null,
        info.hasExecutionDuration()
            ? ProtobufTimeUtils.toJavaDuration(info.getExecutionDuration())
            : null,
        info.hasScheduleTime()
            ? ProtobufTimeUtils.toJavaInstant(info.getScheduleTime())
            : Instant.EPOCH,
        info.getStatus(),
        info.getTaskQueue(),
        SearchAttributesUtil.decodeTyped(info.getSearchAttributes()));
    this.info = info;
    this.dataConverter = dataConverter;
    this.namespace = namespace;
    this.longPollToken = longPollToken;
  }

  private static @Nullable String nullIfEmpty(String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  private ActivityExecutionInfo info() {
    return info;
  }

  /** The raw protobuf info returned by the server for this activity execution. */
  @Nonnull
  public ActivityExecutionInfo getRawInfo() {
    return info;
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

  /** Current or next retry interval. {@code null} if no retries are configured or allowed. */
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

  /** Failure details from the last failed attempt. {@code null} if no failure has occurred. */
  @Nullable
  public io.temporal.api.failure.v1.Failure getLastFailure() {
    return info().hasLastFailure() ? info().getLastFailure() : null;
  }

  /** Identity of the worker that last processed this activity. */
  @Nullable
  public String getLastWorkerIdentity() {
    String w = info().getLastWorkerIdentity();
    return w.isEmpty() ? null : w;
  }

  /**
   * Token for a follow-on {@link UntypedActivityHandle#describe(byte[])} call. Pass this token to
   * long-poll until the activity state changes. {@code null} when the activity is complete.
   */
  @Nullable
  public byte[] getLongPollToken() {
    return longPollToken;
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
  public RetryOptions getRetryOptions() {
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

  /** Whether heartbeat details were recorded for the last attempt. */
  public boolean hasHeartbeatDetails() {
    return info().hasHeartbeatDetails();
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
    if (!info().hasHeartbeatDetails()) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        dataConverter.fromPayloads(
            0, Optional.of(info().getHeartbeatDetails()), valueType, genericType));
  }

  /**
   * The deployment version of the worker that last processed this activity. {@code null} if not
   * available.
   */
  @Nullable
  public WorkerDeploymentVersion getWorkerDeploymentVersion() {
    if (!info().hasLastDeploymentVersion()) {
      return null;
    }
    io.temporal.api.deployment.v1.WorkerDeploymentVersion proto = info().getLastDeploymentVersion();
    return new WorkerDeploymentVersion(proto.getDeploymentName(), proto.getBuildId());
  }

  /** Priority hint for this activity. {@code null} if not set. */
  @Nullable
  public Priority getPriority() {
    if (!info().hasPriority()) {
      return null;
    }
    return ProtoConverters.fromProto(info().getPriority());
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
            new ActivitySerializationContext(
                namespace, null, null, getActivityType(), getTaskQueue(), false))
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
            new ActivitySerializationContext(
                namespace, null, null, getActivityType(), getTaskQueue(), false))
        .fromPayload(info().getUserMetadata().getDetails(), String.class, String.class);
  }
}
