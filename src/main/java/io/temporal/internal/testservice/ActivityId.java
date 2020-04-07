/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.testservice;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.temporal.proto.execution.WorkflowExecution;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

final class ActivityId {

  private final ExecutionId executionId;
  private final String id;

  ActivityId(String namespace, WorkflowExecution execution, String id) {
    this.executionId =
        new ExecutionId(Objects.requireNonNull(namespace), Objects.requireNonNull(execution));
    this.id = Objects.requireNonNull(id);
  }

  ActivityId(String namespace, String workflowId, String runId, String id) {
    this(
        namespace,
        WorkflowExecution.newBuilder().setWorkflowId(workflowId).setRunId(runId).build(),
        id);
  }

  public ActivityId(ExecutionId executionId, String id) {
    this.executionId = executionId;
    this.id = id;
  }

  public ExecutionId getExecutionId() {
    return executionId;
  }

  public String getId() {
    return id;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ActivityId that = (ActivityId) o;

    if (!executionId.equals(that.executionId)) {
      return false;
    }
    return id.equals(that.id);
  }

  @Override
  public int hashCode() {
    int result = executionId.hashCode();
    result = 31 * result + id.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "ActivityId{" + "executionId=" + executionId + ", id='" + id + '\'' + '}';
  }

  /** Used for task tokens. */
  public ByteString toBytes() {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bout);
    try {
      out.writeUTF(executionId.getNamespace());
      WorkflowExecution execution = executionId.getExecution();
      out.writeUTF(execution.getWorkflowId());
      out.writeUTF(execution.getRunId());
      out.writeUTF(id);
      return ByteString.copyFrom(bout.toByteArray());
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
  }

  static ActivityId fromBytes(ByteString serialized) {
    return fromBytes(serialized.toByteArray());
  }

  static ActivityId fromBytes(byte[] serialized) {
    ByteArrayInputStream bin = new ByteArrayInputStream(serialized);
    DataInputStream in = new DataInputStream(bin);
    try {
      String namespace = in.readUTF();
      String workflowId = in.readUTF();
      String runId = in.readUTF();
      String id = in.readUTF();
      return new ActivityId(namespace, workflowId, runId, id);
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
  }

  public WorkflowId getWorkflowId() {
    return new WorkflowId(executionId.getNamespace(), executionId.getExecution().getWorkflowId());
  }
}
