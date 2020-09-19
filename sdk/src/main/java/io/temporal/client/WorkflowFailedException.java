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

package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.RetryState;

/**
 * Indicates that a workflow failed. An original cause of the workflow failure can be retrieved
 * through {@link #getCause()}.
 */
public final class WorkflowFailedException extends WorkflowException {

  private final RetryState retryState;
  private final long workflowTaskCompletedEventId;

  public WorkflowFailedException(
      WorkflowExecution workflowExecution,
      String workflowType,
      long workflowTaskCompletedEventId,
      RetryState retryState,
      Throwable cause) {
    super(
        getMessage(workflowExecution, workflowType, workflowTaskCompletedEventId, retryState),
        workflowExecution,
        workflowType,
        cause);
    this.retryState = retryState;
    this.workflowTaskCompletedEventId = workflowTaskCompletedEventId;
  }

  public RetryState getRetryState() {
    return retryState;
  }

  public long getWorkflowTaskCompletedEventId() {
    return workflowTaskCompletedEventId;
  }

  public static String getMessage(
      WorkflowExecution workflowExecution,
      String workflowType,
      long workflowTaskCompletedEventId,
      RetryState retryState) {
    return "workflowId='"
        + workflowExecution.getWorkflowId()
        + "', runId='"
        + workflowExecution.getRunId()
        + (workflowType == null ? "'" : "', workflowType='" + workflowType + '\'')
        + ", retryState="
        + retryState
        + ", workflowTaskCompletedEventId="
        + workflowTaskCompletedEventId;
  }
}
