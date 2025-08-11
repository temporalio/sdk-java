package io.temporal.internal.testservice;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.testservice.internal.v1.NexusTaskToken;
import java.io.*;
import java.util.Objects;
import javax.annotation.Nonnull;

public class NexusWorkflowTaskToken {

  @Nonnull private final NexusOperationRef ref;
  private final int attempt;
  private final boolean isCancel;

  NexusWorkflowTaskToken(
      @Nonnull String namespace,
      @Nonnull WorkflowExecution execution,
      long scheduledEventId,
      int attempt,
      boolean isCancel) {
    this(
        new ExecutionId(Objects.requireNonNull(namespace), Objects.requireNonNull(execution)),
        scheduledEventId,
        attempt,
        isCancel);
  }

  NexusWorkflowTaskToken(
      @Nonnull String namespace,
      @Nonnull String workflowId,
      @Nonnull String runId,
      long scheduledEventId,
      int attempt,
      boolean isCancel) {
    this(
        namespace,
        WorkflowExecution.newBuilder()
            .setWorkflowId(Objects.requireNonNull(workflowId))
            .setRunId(Objects.requireNonNull(runId))
            .build(),
        scheduledEventId,
        attempt,
        isCancel);
  }

  NexusWorkflowTaskToken(
      @Nonnull ExecutionId executionId, long scheduledEventId, int attempt, boolean isCancel) {
    this(
        new NexusOperationRef(Objects.requireNonNull(executionId), scheduledEventId),
        attempt,
        isCancel);
  }

  public NexusWorkflowTaskToken(@Nonnull NexusOperationRef ref, int attempt, boolean isCancel) {
    this.ref = Objects.requireNonNull(ref);
    this.attempt = attempt;
    this.isCancel = isCancel;
  }

  public static NexusWorkflowTaskToken fromTaskToken(NexusTaskToken nexusTaskToken) {
    return new NexusWorkflowTaskToken(
        nexusTaskToken.getWorkflowCaller().getNamespace(),
        nexusTaskToken.getWorkflowCaller().getExecution(),
        nexusTaskToken.getWorkflowCaller().getScheduledEventId(),
        nexusTaskToken.getAttempt(),
        nexusTaskToken.getCancelled());
  }

  public NexusOperationRef getOperationRef() {
    return ref;
  }

  public long getAttempt() {
    return attempt;
  }

  public boolean isCancel() {
    return isCancel;
  }

  /** Used for task tokens. */
  public ByteString toBytes() {
    return NexusTaskToken.newBuilder()
        .setAttempt(attempt)
        .setCancelled(isCancel)
        .setWorkflowCaller(
            NexusTaskToken.WorkflowCallerTaskToken.newBuilder()
                .setExecution(ref.getExecutionId().getExecution())
                .setNamespace(ref.getExecutionId().getNamespace())
                .setScheduledEventId(ref.getScheduledEventId())
                .build())
        .build()
        .toByteString();
  }
}
