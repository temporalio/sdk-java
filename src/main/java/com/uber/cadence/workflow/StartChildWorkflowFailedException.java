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

import com.uber.cadence.ChildWorkflowExecutionFailedCause;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;

/**
 * Indicates that child workflow failed to start. Currently the only cause is that there is already
 * workflow running with the same ID.
 */
@SuppressWarnings("serial")
public final class StartChildWorkflowFailedException extends ChildWorkflowException {

  private ChildWorkflowExecutionFailedCause failureCause;

  public StartChildWorkflowFailedException(
      long eventId,
      WorkflowExecution workflowExecution,
      WorkflowType workflowType,
      ChildWorkflowExecutionFailedCause cause) {
    super(String.valueOf(cause), eventId, workflowExecution, workflowType);
    this.failureCause = cause;
  }

  /** @return enumeration that contains the cause of the failure */
  public ChildWorkflowExecutionFailedCause getFailureCause() {
    return failureCause;
  }
}
