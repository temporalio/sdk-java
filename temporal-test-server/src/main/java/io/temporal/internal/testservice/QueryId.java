package io.temporal.internal.testservice;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

class QueryId {

  private final ExecutionId executionId;
  private final String queryId;

  QueryId(ExecutionId executionId) {
    this.executionId = Objects.requireNonNull(executionId);
    this.queryId = UUID.randomUUID().toString();
  }

  QueryId(ExecutionId executionId, String queryId) {
    this.executionId = Objects.requireNonNull(executionId);
    this.queryId = queryId;
  }

  public ExecutionId getExecutionId() {
    return executionId;
  }

  String getQueryId() {
    return queryId;
  }

  ByteString toBytes() {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bout);
    addBytes(out);
    return ByteString.copyFrom(bout.toByteArray());
  }

  void addBytes(DataOutputStream out) {
    try {
      executionId.addBytes(out);
      out.writeUTF(queryId);
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
  }

  static QueryId fromBytes(ByteString serialized) {
    ByteArrayInputStream bin = new ByteArrayInputStream(serialized.toByteArray());
    DataInputStream in = new DataInputStream(bin);
    try {
      ExecutionId executionId = ExecutionId.readFromBytes(in);
      String queryId = in.readUTF();
      return new QueryId(executionId, queryId);
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
  }
}
