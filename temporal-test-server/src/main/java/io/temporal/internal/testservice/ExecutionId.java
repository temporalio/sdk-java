package io.temporal.internal.testservice;

import io.grpc.Status;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.internal.common.OptionsUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

public final class ExecutionId {

  private final String namespace;
  private final WorkflowExecution execution;

  public ExecutionId(String namespace, WorkflowExecution execution) {
    this.namespace = Objects.requireNonNull(namespace);
    this.execution = Objects.requireNonNull(execution);
  }

  public ExecutionId(String namespace, String workflowId, String runId) {
    this(
        namespace,
        WorkflowExecution.newBuilder()
            .setWorkflowId(Objects.requireNonNull(workflowId))
            .setRunId(OptionsUtils.safeGet(runId))
            .build());
  }

  public String getNamespace() {
    return namespace;
  }

  public WorkflowExecution getExecution() {
    return execution;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ExecutionId that = (ExecutionId) o;

    if (!namespace.equals(that.namespace)) {
      return false;
    }
    return execution.equals(that.execution);
  }

  @Override
  public int hashCode() {
    int result = namespace.hashCode();
    result = 31 * result + execution.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "ExecutionId{" + "namespace='" + namespace + '\'' + ", execution=" + execution + '}';
  }

  /** Used for task tokens. */
  byte[] toBytes() {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bout);
    try {
      addBytes(out);
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
    return bout.toByteArray();
  }

  void addBytes(DataOutputStream out) throws IOException {
    out.writeUTF(namespace);
    out.writeUTF(execution.getWorkflowId());
    if (!execution.getRunId().isEmpty()) {
      out.writeUTF(execution.getRunId());
    }
  }

  static ExecutionId fromBytes(byte[] serialized) {
    ByteArrayInputStream bin = new ByteArrayInputStream(serialized);
    DataInputStream in = new DataInputStream(bin);
    try {
      return readFromBytes(in);
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
  }

  static ExecutionId readFromBytes(DataInputStream in) throws IOException {
    String namespace = in.readUTF();
    String workflowId = in.readUTF();
    String runId = null;
    if (in.available() > 0) {
      runId = in.readUTF();
    }
    return new ExecutionId(namespace, workflowId, runId);
  }

  public WorkflowId getWorkflowId() {
    return new WorkflowId(namespace, execution.getWorkflowId());
  }
}
