/*
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

package com.uber.cadence.internal.testservice;

import com.google.common.base.Throwables;
import com.uber.cadence.InternalServiceError;
import com.uber.cadence.WorkflowExecution;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

final class ActivityId {

  private final ExecutionId executionId;
  private final String id;

  ActivityId(String domain, WorkflowExecution execution, String id) {
    this.executionId =
        new ExecutionId(Objects.requireNonNull(domain), Objects.requireNonNull(execution));
    this.id = Objects.requireNonNull(id);
  }

  ActivityId(String domain, String workflowId, String runId, String id) {
    this(domain, new WorkflowExecution().setWorkflowId(workflowId).setRunId(runId), id);
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
  public byte[] toBytes() throws InternalServiceError {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bout);
    try {
      out.writeUTF(executionId.getDomain());
      WorkflowExecution execution = executionId.getExecution();
      out.writeUTF(execution.getWorkflowId());
      out.writeUTF(execution.getRunId());
      out.writeUTF(id);
      return bout.toByteArray();
    } catch (IOException e) {
      throw new InternalServiceError(Throwables.getStackTraceAsString(e));
    }
  }

  static ActivityId fromBytes(byte[] serialized) throws InternalServiceError {
    ByteArrayInputStream bin = new ByteArrayInputStream(serialized);
    DataInputStream in = new DataInputStream(bin);
    try {
      String domain = in.readUTF();
      String workflowId = in.readUTF();
      String runId = in.readUTF();
      String id = in.readUTF();
      return new ActivityId(domain, workflowId, runId, id);
    } catch (IOException e) {
      throw new InternalServiceError(Throwables.getStackTraceAsString(e));
    }
  }

  public WorkflowId getWorkflowId() {
    return new WorkflowId(executionId.getDomain(), executionId.getExecution().getWorkflowId());
  }
}
