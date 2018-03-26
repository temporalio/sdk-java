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

final class ExecutionId {

  private final String domain;
  private final WorkflowExecution execution;

  ExecutionId(String domain, WorkflowExecution execution) {
    this.domain = Objects.requireNonNull(domain);
    this.execution = Objects.requireNonNull(execution);
  }

  ExecutionId(String domain, String workflowId, String runId) {
    this(
        domain,
        new WorkflowExecution().setWorkflowId(Objects.requireNonNull(workflowId)).setRunId(runId));
  }

  public String getDomain() {
    return domain;
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

    if (!domain.equals(that.domain)) {
      return false;
    }
    return execution.equals(that.execution);
  }

  @Override
  public int hashCode() {
    int result = domain.hashCode();
    result = 31 * result + execution.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "ExecutionId{" + "domain='" + domain + '\'' + ", execution=" + execution + '}';
  }

  /** Used for task tokens. */
  byte[] toBytes() throws InternalServiceError {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bout);
    try {
      addBytes(out);
    } catch (IOException e) {
      throw new InternalServiceError(Throwables.getStackTraceAsString(e));
    }
    return bout.toByteArray();
  }

  void addBytes(DataOutputStream out) throws IOException {
    out.writeUTF(domain);
    out.writeUTF(execution.getWorkflowId());
    if (execution.getRunId() != null) {
      out.writeUTF(execution.getRunId());
    }
  }

  static ExecutionId fromBytes(byte[] serialized) throws InternalServiceError {
    ByteArrayInputStream bin = new ByteArrayInputStream(serialized);
    DataInputStream in = new DataInputStream(bin);
    try {
      return readFromBytes(in);
    } catch (IOException e) {
      throw new InternalServiceError(Throwables.getStackTraceAsString(e));
    }
  }

  static ExecutionId readFromBytes(DataInputStream in) throws IOException {
    String domain = in.readUTF();
    String workflowId = in.readUTF();
    String runId = null;
    if (in.available() > 0) {
      runId = in.readUTF();
    }
    return new ExecutionId(domain, workflowId, runId);
  }

  public WorkflowId getWorkflowId() {
    return new WorkflowId(domain, execution.getWorkflowId());
  }
}
