package io.temporal.internal.testservice;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.temporal.api.common.v1.WorkflowExecution;
import java.io.*;
import java.util.Objects;
import javax.annotation.Nonnull;

public class NexusOperationRef {

  @Nonnull private final ExecutionId executionId;
  private final long scheduledEventId;

  NexusOperationRef(
      @Nonnull String namespace, @Nonnull WorkflowExecution execution, long scheduledEventId) {
    this(
        new ExecutionId(Objects.requireNonNull(namespace), Objects.requireNonNull(execution)),
        scheduledEventId);
  }

  NexusOperationRef(
      @Nonnull String namespace,
      @Nonnull String workflowId,
      @Nonnull String runId,
      long scheduledEventId) {
    this(
        namespace,
        WorkflowExecution.newBuilder()
            .setWorkflowId(Objects.requireNonNull(workflowId))
            .setRunId(Objects.requireNonNull(runId))
            .build(),
        scheduledEventId);
  }

  public NexusOperationRef(@Nonnull ExecutionId executionId, long scheduledEventId) {
    this.executionId = Objects.requireNonNull(executionId);
    this.scheduledEventId = scheduledEventId;
  }

  public ExecutionId getExecutionId() {
    return executionId;
  }

  public long getScheduledEventId() {
    return scheduledEventId;
  }

  public ByteString toBytes() {
    try (ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bout)) {
      out.writeUTF(executionId.getNamespace());
      WorkflowExecution execution = executionId.getExecution();
      out.writeUTF(execution.getWorkflowId());
      out.writeUTF(execution.getRunId());
      out.writeLong(scheduledEventId);
      return ByteString.copyFrom(bout.toByteArray());
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
  }

  public static NexusOperationRef fromBytes(ByteString serialized) {
    return fromBytes(serialized.toByteArray());
  }

  public static NexusOperationRef fromBytes(byte[] serialized) {
    ByteArrayInputStream bin = new ByteArrayInputStream(serialized);
    DataInputStream in = new DataInputStream(bin);
    try {
      String namespace = in.readUTF();
      String workflowId = in.readUTF();
      String runId = in.readUTF();
      long scheduledEventId = in.readLong();
      return new NexusOperationRef(namespace, workflowId, runId, scheduledEventId);
    } catch (IOException e) {
      throw Status.INVALID_ARGUMENT
          .withCause(e)
          .withDescription(e.getMessage())
          .asRuntimeException();
    }
  }
}
