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

package com.uber.cadence.client;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.workflow.ChildWorkflowException;
import java.util.Optional;

/**
 * Base exception for all workflow failures returned by an external client. Note that inside a
 * workflow implementation child workflows throw subclasses of {@link ChildWorkflowException}.
 */
public class WorkflowException extends RuntimeException {

  private final WorkflowExecution execution;
  private final Optional<String> workflowType;

  protected WorkflowException(
      String message, WorkflowExecution execution, Optional<String> workflowType, Throwable cause) {
    super(getMessage(message, execution, workflowType), cause);
    this.execution = execution;
    this.workflowType = workflowType;
  }

  private static String getMessage(
      String message, WorkflowExecution execution, Optional<String> workflowType) {
    StringBuilder result = new StringBuilder();
    if (message != null) {
      result.append(message);
      result.append(", ");
    }
    if (workflowType.isPresent()) {
      result.append("WorkflowType=\"");
      result.append(workflowType.get());
    }
    if (execution != null) {
      if (result.length() > 0) {
        result.append("\", ");
      }
      result.append("WorkflowExecution=\"");
      result.append(execution);
      result.append("\"");
    }
    return result.toString();
  }

  public WorkflowExecution getExecution() {
    return execution;
  }

  public Optional<String> getWorkflowType() {
    return workflowType;
  }
}
