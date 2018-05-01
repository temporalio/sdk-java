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

package com.uber.cadence.workflow;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;

/** Base exception for failures of a child workflow. */
@SuppressWarnings("serial")
public abstract class ChildWorkflowException extends WorkflowOperationException {

  private final WorkflowExecution workflowExecution;

  private final WorkflowType workflowType;

  protected ChildWorkflowException(
      String message,
      long eventId,
      WorkflowExecution workflowExecution,
      WorkflowType workflowType) {
    super(
        message
            + " WorkflowType=\""
            + workflowType.getName()
            + "\", ID=\""
            + workflowExecution.getWorkflowId()
            + "\", RunID=\""
            + workflowExecution.getRunId()
            + ", EventID="
            + eventId,
        eventId);

    this.workflowExecution = workflowExecution;
    this.workflowType = workflowType;
  }

  public WorkflowExecution getWorkflowExecution() {
    return workflowExecution;
  }

  public WorkflowType getWorkflowType() {
    return workflowType;
  }
}
