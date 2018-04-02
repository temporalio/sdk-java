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
import java.util.Optional;

/**
 * Indicates that a workflow failed. An original cause of the workflow failure can be retrieved
 * through {@link #getCause()}.
 */
public final class WorkflowFailureException extends WorkflowException {

  private final long decisionTaskCompletedEventId;

  public WorkflowFailureException(
      WorkflowExecution execution,
      Optional<String> workflowType,
      long decisionTaskCompletedEventId,
      Throwable failure) {
    super(getMessage(execution, workflowType), execution, workflowType, failure);
    this.decisionTaskCompletedEventId = decisionTaskCompletedEventId;
  }

  private static String getMessage(WorkflowExecution execution, Optional<String> workflowType) {
    StringBuilder result = new StringBuilder();
    if (workflowType.isPresent()) {
      result.append("WorkflowType=\"");
      result.append(workflowType.get());
      result.append("\", ");
    }
    result.append("WorkflowID=\"");
    result.append(execution.getWorkflowId());
    result.append("\", RunID=\"");
    result.append(execution.getRunId());
    return result.toString();
  }

  public long getDecisionTaskCompletedEventId() {
    return decisionTaskCompletedEventId;
  }
}
