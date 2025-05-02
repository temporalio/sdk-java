package io.temporal.internal.testservice;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.temporal.api.common.v1.WorkflowExecution;
import java.io.*;
import java.util.Objects;
import javax.annotation.Nonnull;

class ActivityTaskToken {
  @Nonnull private final ExecutionId executionId;
  private final long scheduledEventId;
  private final int attempt;

  ActivityTaskToken(
      @Nonnull String namespace,
      @Nonnull WorkflowExecution execution,
      long scheduledEventId,
      int attempt) {
    this(
        new ExecutionId(Objects.requireNonNull(namespace), Objects.requireNonNull(execution)),
        scheduledEventId,
        attempt);
  }

  ActivityTaskToken(
      @Nonnull String namespace,
      @Nonnull String workflowId,
      @Nonnull String runId,
      long scheduledEventId,
      int attempt) {
    this(
        namespace,
        WorkflowExecution.newBuilder()
            .setWorkflowId(Objects.requireNonNull(workflowId))
            .setRunId(Objects.requireNonNull(runId))
            .build(),
        scheduledEventId,
        attempt);
  }

  ActivityTaskToken(@Nonnull ExecutionId executionId, long scheduledEventId, int attempt) {
    this.executionId = Objects.requireNonNull(executionId);
    this.scheduledEventId = scheduledEventId;
    this.attempt = attempt;
  }

  @Nonnull
  public ExecutionId getExecutionId() {
    return executionId;
  }

  public long getScheduledEventId() {
    return scheduledEventId;
  }

  public long getAttempt() {
    return attempt;
  }

  /** Used for task tokens. */
  public ByteString toBytes() {
    try (ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bout)) {
      out.writeUTF(executionId.getNamespace());
      WorkflowExecution execution = executionId.getExecution();
      out.writeUTF(execution.getWorkflowId());
      out.writeUTF(execution.getRunId());
      out.writeLong(scheduledEventId);
      out.writeInt(attempt);
      return ByteString.copyFrom(bout.toByteArray());
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
  }

  static ActivityTaskToken fromBytes(ByteString serialized) {
    return fromBytes(serialized.toByteArray());
  }

  static ActivityTaskToken fromBytes(byte[] serialized) {
    try (ByteArrayInputStream bin = new ByteArrayInputStream(serialized);
        DataInputStream in = new DataInputStream(bin)) {
      String namespace = in.readUTF();
      String workflowId = in.readUTF();
      String runId = in.readUTF();
      long scheduledEventId = in.readLong();
      int attempt = in.readInt();
      return new ActivityTaskToken(namespace, workflowId, runId, scheduledEventId, attempt);
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ActivityTaskToken that = (ActivityTaskToken) o;
    return scheduledEventId == that.scheduledEventId
        && attempt == that.attempt
        && executionId.equals(that.executionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(executionId, scheduledEventId, attempt);
  }

  @Override
  public String toString() {
    return "ActivityTaskToken{"
        + "executionId="
        + executionId
        + ", scheduledEventId="
        + scheduledEventId
        + ", attempt="
        + attempt
        + '}';
  }
}
