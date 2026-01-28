package io.temporal.internal.testservice;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

final class WorkflowTaskToken {

  private final ExecutionId executionId;
  private final int historySize;

  WorkflowTaskToken(ExecutionId executionId, int historySize) {
    this.executionId = Objects.requireNonNull(executionId);
    this.historySize = historySize;
  }

  ExecutionId getExecutionId() {
    return executionId;
  }

  int getHistorySize() {
    return historySize;
  }

  /** Used for task tokens. */
  ByteString toBytes() {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bout);
    try {
      addBytes(out);
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
    return ByteString.copyFrom(bout.toByteArray());
  }

  private void addBytes(DataOutputStream out) throws IOException {
    executionId.addBytes(out);
    out.writeInt(historySize);
  }

  static WorkflowTaskToken fromBytes(ByteString serialized) {
    ByteArrayInputStream bin = new ByteArrayInputStream(serialized.toByteArray());
    DataInputStream in = new DataInputStream(bin);
    try {
      ExecutionId executionId = ExecutionId.readFromBytes(in);
      int historySize = in.readInt();
      return new WorkflowTaskToken(executionId, historySize);
    } catch (IOException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Failure parsing workflow task token")
          .withCause(e)
          .asRuntimeException();
    }
  }
}
