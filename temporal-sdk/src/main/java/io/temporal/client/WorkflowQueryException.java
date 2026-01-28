package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;

public class WorkflowQueryException extends WorkflowException {

  public WorkflowQueryException(WorkflowExecution execution, String workflowType, Throwable cause) {
    super(execution, workflowType, cause);
  }
}
