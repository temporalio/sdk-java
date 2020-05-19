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

package io.temporal.internal.common;

import io.temporal.proto.common.Payloads;
import io.temporal.proto.execution.WorkflowExecution;

public class TerminateWorkflowExecutionParameters {

  private WorkflowExecution workflowExecution;

  private String reason;

  private Payloads details;

  public TerminateWorkflowExecutionParameters() {}

  public TerminateWorkflowExecutionParameters(
      WorkflowExecution workflowExecution, String reason, Payloads details) {
    this.workflowExecution = workflowExecution;
    this.reason = reason;
    this.details = details;
  }

  public WorkflowExecution getWorkflowExecution() {
    return workflowExecution;
  }

  public void setWorkflowExecution(WorkflowExecution workflowExecution) {
    this.workflowExecution = workflowExecution;
  }

  public TerminateWorkflowExecutionParameters withWorkflowExecution(
      WorkflowExecution workflowExecution) {
    this.workflowExecution = workflowExecution;
    return this;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public TerminateWorkflowExecutionParameters withReason(String reason) {
    this.reason = reason;
    return this;
  }

  public Payloads getDetails() {
    return details;
  }

  public void setDetails(Payloads details) {
    this.details = details;
  }

  public TerminateWorkflowExecutionParameters withDetails(Payloads details) {
    this.details = details;
    return this;
  }
}
