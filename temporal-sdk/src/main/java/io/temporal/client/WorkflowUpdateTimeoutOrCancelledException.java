package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;

public class WorkflowUpdateTimeoutOrCancelledException extends WorkflowServiceException {
  public WorkflowUpdateTimeoutOrCancelledException(
      WorkflowExecution execution, String updateId, String updateName, Throwable cause) {
    super(execution, "", cause);
  }
}
