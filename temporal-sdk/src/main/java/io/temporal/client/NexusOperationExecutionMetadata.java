package io.temporal.client;

import io.temporal.api.enums.v1.NexusOperationExecutionStatus;
import io.temporal.api.nexus.v1.NexusOperationExecutionListInfo;
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
 * Information about a standalone Nexus operation execution returned by {@link
 * NexusClient#listNexusOperationExecutions}.
 */
@Experimental
public class NexusOperationExecutionMetadata {

  private final @Nullable NexusOperationExecutionListInfo rawListInfo;
  private final String operationId;
  private final @Nullable String runId;
  private final @Nullable String endpoint;
  private final @Nullable String service;
  private final @Nullable String operation;
  private final @Nullable Instant scheduledTime;
  private final @Nullable Instant closeTime;
  private final NexusOperationExecutionStatus status;
  private final SearchAttributes searchAttributes;
  private final long stateTransitionCount;
  private final @Nullable Duration executionDuration;

  NexusOperationExecutionMetadata(
      @Nullable NexusOperationExecutionListInfo rawListInfo,
      String operationId,
      @Nullable String runId,
      @Nullable String endpoint,
      @Nullable String service,
      @Nullable String operation,
      @Nullable Instant scheduledTime,
      @Nullable Instant closeTime,
      NexusOperationExecutionStatus status,
      SearchAttributes searchAttributes,
      long stateTransitionCount,
      @Nullable Duration executionDuration) {
    this.rawListInfo = rawListInfo;
    this.operationId = operationId;
    this.runId = runId;
    this.endpoint = endpoint;
    this.service = service;
    this.operation = operation;
    this.scheduledTime = scheduledTime;
    this.closeTime = closeTime;
    this.status = status;
    this.searchAttributes = searchAttributes;
    this.stateTransitionCount = stateTransitionCount;
    this.executionDuration = executionDuration;
  }

  static @Nullable String nullIfEmpty(String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  public static NexusOperationExecutionMetadata fromListInfo(NexusOperationExecutionListInfo info) {
    return new NexusOperationExecutionMetadata(
        info,
        info.getOperationId(),
        nullIfEmpty(info.getRunId()),
        nullIfEmpty(info.getEndpoint()),
        nullIfEmpty(info.getService()),
        nullIfEmpty(info.getOperation()),
        info.hasScheduleTime() ? ProtobufTimeUtils.toJavaInstant(info.getScheduleTime()) : null,
        info.hasCloseTime() ? ProtobufTimeUtils.toJavaInstant(info.getCloseTime()) : null,
        info.getStatus(),
        SearchAttributesUtil.decodeTyped(info.getSearchAttributes()),
        info.getStateTransitionCount(),
        info.hasExecutionDuration()
            ? ProtobufTimeUtils.toJavaDuration(info.getExecutionDuration())
            : null);
  }

  /**
   * The raw protobuf list info from the server. Only present when this instance was created via
   * {@link #fromListInfo}.
   */
  @Nullable
  public NexusOperationExecutionListInfo getRawListInfo() {
    return rawListInfo;
  }

  /** The user-assigned identifier for this operation. */
  @Nonnull
  public String getOperationId() {
    return operationId;
  }

  /** The server-assigned run ID for this operation execution. May be {@code null}. */
  @Nullable
  public String getRunId() {
    return runId;
  }

  /** The Nexus endpoint name this operation targets. {@code null} if the server omitted it. */
  @Nullable
  public String getEndpoint() {
    return endpoint;
  }

  /** The Nexus service name on the endpoint. {@code null} if the server omitted it. */
  @Nullable
  public String getService() {
    return service;
  }

  /** The Nexus operation name within the service. {@code null} if the server omitted it. */
  @Nullable
  public String getOperation() {
    return operation;
  }

  /**
   * Time when the operation was originally scheduled via a {@code StartNexusOperation} request.
   * {@code null} if the server omitted it.
   */
  @Nullable
  public Instant getScheduledTime() {
    return scheduledTime;
  }

  /** Time the operation transitioned to a terminal status. {@code null} while still running. */
  @Nullable
  public Instant getCloseTime() {
    return closeTime;
  }

  /** General status of the operation execution. */
  @Nonnull
  public NexusOperationExecutionStatus getStatus() {
    return status;
  }

  /** Search attributes attached to this operation execution. */
  @Nonnull
  public SearchAttributes getSearchAttributes() {
    return searchAttributes;
  }

  /** Server-tracked count of state transitions; updated on terminal status. */
  public long getStateTransitionCount() {
    return stateTransitionCount;
  }

  /** Close time minus scheduled time. {@code null} while still running. */
  @Nullable
  public Duration getExecutionDuration() {
    return executionDuration;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    NexusOperationExecutionMetadata that = (NexusOperationExecutionMetadata) o;
    return stateTransitionCount == that.stateTransitionCount
        && Objects.equals(operationId, that.operationId)
        && Objects.equals(runId, that.runId)
        && Objects.equals(endpoint, that.endpoint)
        && Objects.equals(service, that.service)
        && Objects.equals(operation, that.operation)
        && Objects.equals(scheduledTime, that.scheduledTime)
        && Objects.equals(closeTime, that.closeTime)
        && status == that.status
        && Objects.equals(searchAttributes, that.searchAttributes)
        && Objects.equals(executionDuration, that.executionDuration);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        operationId,
        runId,
        endpoint,
        service,
        operation,
        scheduledTime,
        closeTime,
        status,
        searchAttributes,
        stateTransitionCount,
        executionDuration);
  }

  @Override
  public String toString() {
    return "NexusOperationExecutionMetadata{"
        + "operationId='"
        + operationId
        + "', runId='"
        + runId
        + "', endpoint='"
        + endpoint
        + "', service='"
        + service
        + "', operation='"
        + operation
        + "', status="
        + status
        + ", scheduledTime="
        + scheduledTime
        + ", closeTime="
        + closeTime
        + ", executionDuration="
        + executionDuration
        + ", searchAttributes="
        + searchAttributes
        + '}';
  }
}
