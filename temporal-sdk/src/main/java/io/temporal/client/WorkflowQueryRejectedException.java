package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;

public class WorkflowQueryRejectedException extends WorkflowQueryException {

  public WorkflowQueryRejectedException(
      WorkflowExecution execution, String workflowType, Throwable cause) {
    super(execution, workflowType, cause);
  }
}
