package io.temporal.client;

import io.temporal.api.enums.v1.PendingNexusOperationState;
import io.temporal.api.nexus.v1.NexusOperationExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeNexusOperationExecutionResponse;
import io.temporal.common.Experimental;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Detailed information about a standalone Nexus operation execution, returned by {@link
 * UntypedNexusOperationHandle#describe()}.
 */
@Experimental
public final class NexusOperationExecutionDescription extends NexusOperationExecutionMetadata {

  private final DescribeNexusOperationExecutionResponse response;
  private final NexusOperationExecutionInfo info;
  private final DataConverter dataConverter;

  public NexusOperationExecutionDescription(
      DescribeNexusOperationExecutionResponse response,
      DataConverter dataConverter,
      String namespace) {
    super(
        /* rawListInfo= */ null,
        response.getInfo().getOperationId(),
        nullIfEmpty(response.getInfo().getRunId()),
        response.getInfo().getEndpoint(),
        response.getInfo().getService(),
        response.getInfo().getOperation(),
        response.getInfo().hasScheduleTime()
            ? ProtobufTimeUtils.toJavaInstant(response.getInfo().getScheduleTime())
            : Instant.EPOCH,
        response.getInfo().hasCloseTime()
            ? ProtobufTimeUtils.toJavaInstant(response.getInfo().getCloseTime())
            : null,
        response.getInfo().getStatus(),
        SearchAttributesUtil.decodeTyped(response.getInfo().getSearchAttributes()),
        response.getInfo().getStateTransitionCount(),
        response.getInfo().hasExecutionDuration()
            ? ProtobufTimeUtils.toJavaDuration(response.getInfo().getExecutionDuration())
            : null);
    this.response = response;
    this.info = response.getInfo();
    this.dataConverter = dataConverter;
  }

  private static @Nullable String nullIfEmpty(String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  /** Underlying proto response. Exposed while the Nexus SDK surface is still experimental. */
  @Nonnull
  public DescribeNexusOperationExecutionResponse getRawResponse() {
    return response;
  }

  /** The raw protobuf info returned by the server for this operation execution. */
  @Nonnull
  public NexusOperationExecutionInfo getRawInfo() {
    return info;
  }

  /** Current attempt number for the start request (starts at 1). */
  public int getAttempt() {
    return info.getAttempt();
  }

  /**
   * Detailed run state (e.g. scheduled, started, backing off). Only meaningful when {@link
   * #getStatus()} is {@code NEXUS_OPERATION_EXECUTION_STATUS_RUNNING}.
   */
  @Nonnull
  public PendingNexusOperationState getRunState() {
    return info.getState();
  }

  /** Total time the caller is willing to wait for the operation to complete, including retries. */
  @Nullable
  public Duration getScheduleToCloseTimeout() {
    return info.hasScheduleToCloseTimeout()
        ? ProtobufTimeUtils.toJavaDuration(info.getScheduleToCloseTimeout())
        : null;
  }

  /** Maximum time the start request may wait before being delivered to the handler. */
  @Nullable
  public Duration getScheduleToStartTimeout() {
    return info.hasScheduleToStartTimeout()
        ? ProtobufTimeUtils.toJavaDuration(info.getScheduleToStartTimeout())
        : null;
  }

  /** Maximum time for a single start-request attempt. */
  @Nullable
  public Duration getStartToCloseTimeout() {
    return info.hasStartToCloseTimeout()
        ? ProtobufTimeUtils.toJavaDuration(info.getStartToCloseTimeout())
        : null;
  }

  /** Scheduled time plus schedule-to-close timeout. */
  @Nullable
  public Instant getExpirationTime() {
    return info.hasExpirationTime()
        ? ProtobufTimeUtils.toJavaInstant(info.getExpirationTime())
        : null;
  }

  /** Time the last start-request attempt completed (succeeded or failed). */
  @Nullable
  public Instant getLastAttemptCompleteTime() {
    return info.hasLastAttemptCompleteTime()
        ? ProtobufTimeUtils.toJavaInstant(info.getLastAttemptCompleteTime())
        : null;
  }

  /** Failure from the last start-request attempt. {@code null} if no failure has occurred. */
  @Nullable
  public Exception getLastAttemptFailure() {
    return info.hasLastAttemptFailure()
        ? dataConverter.failureToException(info.getLastAttemptFailure())
        : null;
  }

  /** Time when the next start-request attempt will be scheduled. */
  @Nullable
  public Instant getNextAttemptScheduleTime() {
    return info.hasNextAttemptScheduleTime()
        ? ProtobufTimeUtils.toJavaInstant(info.getNextAttemptScheduleTime())
        : null;
  }

  /** Cancellation details if cancellation was requested; {@code null} otherwise. */
  @Nullable
  public NexusOperationCancellationInfo getCancellationInfo() {
    return info.hasCancellationInfo()
        ? new NexusOperationCancellationInfo(info.getCancellationInfo(), dataConverter)
        : null;
  }

  /**
   * Additional context for why the operation is blocked. Set only when {@link #getRunState()} is
   * {@code BLOCKED}.
   */
  @Nullable
  public String getBlockedReason() {
    String r = info.getBlockedReason();
    return r.isEmpty() ? null : r;
  }

  /**
   * Server-generated request ID used as an idempotency token when submitting the start request to
   * the operation handler.
   */
  @Nullable
  public String getHandlerRequestId() {
    String r = info.getRequestId();
    return r.isEmpty() ? null : r;
  }

  /** Operation token returned by the handler; set only for asynchronous operations after start. */
  @Nullable
  public String getOperationToken() {
    String t = info.getOperationToken();
    return t.isEmpty() ? null : t;
  }

  /** Identity of the client that started this operation. */
  @Nullable
  public String getIdentity() {
    String i = info.getIdentity();
    return i.isEmpty() ? null : i;
  }

  /**
   * Whether the operation input payload is present on this description. Set only when {@link
   * UntypedNexusOperationHandle#describe()} was called with {@code includeInput=true}.
   */
  public boolean hasInput() {
    return response.hasInput();
  }

  /**
   * Deserializes the operation input into the given type. Returns {@link Optional#empty()} if no
   * input is present (either the operation was started without one or {@code includeInput} was
   * false on the describe call).
   *
   * @param valueType the class to deserialize the input into
   */
  public <V> Optional<V> getInput(Class<V> valueType) {
    return getInput(valueType, valueType);
  }

  /**
   * Deserializes the operation input into the given generic type. Returns {@link Optional#empty()}
   * if no input is present.
   *
   * @param valueType the class to deserialize the input into
   * @param genericType the generic type for deserialization; may equal {@code valueType}
   */
  public <V> Optional<V> getInput(Class<V> valueType, Type genericType) {
    if (!response.hasInput()) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        dataConverter.fromPayload(response.getInput(), valueType, genericType));
  }

  /**
   * Whether the operation's success result is present. Set only when {@link
   * UntypedNexusOperationHandle#describe()} was called with {@code includeOutcome=true} and the
   * operation completed successfully.
   */
  public boolean hasResult() {
    return response.hasResult();
  }

  /**
   * Deserializes the operation's success result. Returns {@link Optional#empty()} if no result is
   * present (operation still running, completed with a failure, or {@code includeOutcome} was
   * false).
   *
   * @param valueType the class to deserialize the result into
   */
  public <V> Optional<V> getResult(Class<V> valueType) {
    return getResult(valueType, valueType);
  }

  /**
   * Deserializes the operation's success result into the given generic type. Returns {@link
   * Optional#empty()} if no result is present.
   *
   * @param valueType the class to deserialize the result into
   * @param genericType the generic type for deserialization; may equal {@code valueType}
   */
  public <V> Optional<V> getResult(Class<V> valueType, Type genericType) {
    if (!response.hasResult()) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        dataConverter.fromPayload(response.getResult(), valueType, genericType));
  }

  /**
   * Operation failure as a thrown-style exception. Returns {@code null} if the operation did not
   * complete with a failure or if {@code includeOutcome} was false on the describe call.
   */
  @Nullable
  public Exception getFailure() {
    return response.hasFailure() ? dataConverter.failureToException(response.getFailure()) : null;
  }
}
