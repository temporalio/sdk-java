/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.RetryState;

/**
 * Indicates that a workflow failed. An original cause of the workflow failure can be retrieved
 * through {@link #getCause()}.
 */
public final class WorkflowFailedException extends WorkflowException {

  private final EventType workflowCloseEventType;
  private final RetryState retryState;
  private final long workflowTaskCompletedEventId;

  public WorkflowFailedException(
      WorkflowExecution workflowExecution,
      String workflowType,
      EventType workflowCloseEventType,
      long workflowTaskCompletedEventId,
      RetryState retryState,
      Throwable cause) {
    super(
        getMessage(
            workflowExecution,
            workflowType,
            workflowCloseEventType,
            workflowTaskCompletedEventId,
            retryState),
        workflowExecution,
        workflowType,
        cause);
    this.workflowCloseEventType = workflowCloseEventType;
    this.retryState = retryState;
    this.workflowTaskCompletedEventId = workflowTaskCompletedEventId;
  }

  public RetryState getRetryState() {
    return retryState;
  }

  /**
   * This value is defined only if workflow failure is caused by an explicit WORKFLOW_TASK_COMPLETED
   * command. If workflow timed out, was cancelled or terminated, this value is undefined.
   *
   * @return eventId of the WORKFLOW_TASK_COMPLETED event that reported (caused)
   *     WORKFLOW_EXECUTION_FAILED command. -1 if undefined.
   */
  public long getWorkflowTaskCompletedEventId() {
    return workflowTaskCompletedEventId;
  }

  /**
   * Returns Event Type that caused {@code this} exception. This Event Type should be one of
   *
   * <ul>
   *   <li>{@link EventType#EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED}
   *   <li>{@link EventType#EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED}
   *   <li>{@link EventType#EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT}
   *   <li>{@link EventType#EVENT_TYPE_WORKFLOW_EXECUTION_FAILED}
   * </ul>
   *
   * @return event type that caused {@code this} exception
   */
  public EventType getWorkflowCloseEventType() {
    return workflowCloseEventType;
  }

  private static String getMessage(
      WorkflowExecution workflowExecution,
      String workflowType,
      EventType closeEventType,
      long workflowTaskCompletedEventId,
      RetryState retryState) {
    return "Workflow execution "
        + "{"
        + "workflowId='"
        + workflowExecution.getWorkflowId()
        + "', runId='"
        + workflowExecution.getRunId()
        + (workflowType == null ? "'" : "', workflowType='" + workflowType + "'")
        + "} "
        + getAction(closeEventType)
        + ". Metadata: "
        + "{"
        + "closeEventType='"
        + closeEventType
        + "', retryState='"
        + retryState
        + (workflowTaskCompletedEventId == -1
            ? "'"
            : "', workflowTaskCompletedEventId=" + workflowTaskCompletedEventId + "'")
        + "}";
  }

  private static String getAction(EventType closeEventType) {
    switch (closeEventType) {
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
        return "was cancelled";
      case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
        return "was terminated";
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
        return "timed out";
      case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
        return "failed";
      default:
        return "failed with an unexpected closing event type " + closeEventType;
    }
  }
}
