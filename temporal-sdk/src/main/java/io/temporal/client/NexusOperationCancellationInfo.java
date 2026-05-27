package io.temporal.client;

import io.temporal.api.enums.v1.NexusOperationCancellationState;
import io.temporal.api.nexus.v1.NexusOperationExecutionCancellationInfo;
import io.temporal.common.Experimental;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.time.Instant;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Information about a cancellation request issued against a standalone Nexus operation execution.
 * Returned by {@link NexusClientOperationExecutionDescription#getCancellationInfo()}.
 */
@Experimental
public final class NexusOperationCancellationInfo {

  private final NexusOperationExecutionCancellationInfo info;
  private final DataConverter dataConverter;

  NexusOperationCancellationInfo(
      NexusOperationExecutionCancellationInfo info, DataConverter dataConverter) {
    this.info = info;
    this.dataConverter = dataConverter;
  }

  /** The raw protobuf info returned by the server. */
  @Nonnull
  public NexusOperationExecutionCancellationInfo getRawInfo() {
    return info;
  }

  /** Time when cancellation was originally requested. */
  @Nullable
  public Instant getRequestedTime() {
    return info.hasRequestedTime()
        ? ProtobufTimeUtils.toJavaInstant(info.getRequestedTime())
        : null;
  }

  /** Current state of cancellation-request delivery to the operation handler. */
  @Nonnull
  public NexusOperationCancellationState getState() {
    return info.getState();
  }

  /**
   * Current attempt number for delivering the cancel request to the handler. Represents a minimum
   * bound — the value is incremented after the attempt completes.
   */
  public int getAttempt() {
    return info.getAttempt();
  }

  /** Time the last cancel-delivery attempt completed. */
  @Nullable
  public Instant getLastAttemptCompleteTime() {
    return info.hasLastAttemptCompleteTime()
        ? ProtobufTimeUtils.toJavaInstant(info.getLastAttemptCompleteTime())
        : null;
  }

  /**
   * Failure from the last cancel-delivery attempt. {@code null} if no failure has occurred yet.
   */
  @Nullable
  public Exception getLastAttemptFailure() {
    return info.hasLastAttemptFailure()
        ? dataConverter.failureToException(info.getLastAttemptFailure())
        : null;
  }

  /** Time when the next cancel-delivery attempt is scheduled. */
  @Nullable
  public Instant getNextAttemptScheduleTime() {
    return info.hasNextAttemptScheduleTime()
        ? ProtobufTimeUtils.toJavaInstant(info.getNextAttemptScheduleTime())
        : null;
  }

  /**
   * Additional context for why cancel delivery is blocked. Set only when {@link #getState()}
   * indicates a blocked state.
   */
  @Nullable
  public String getBlockedReason() {
    String r = info.getBlockedReason();
    return r.isEmpty() ? null : r;
  }

  /** The human-readable reason supplied with the original cancel request, if any. */
  @Nullable
  public String getReason() {
    String r = info.getReason();
    return r.isEmpty() ? null : r;
  }
}
