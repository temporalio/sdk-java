package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import javax.annotation.Nonnull;

public class WorkflowServiceException extends WorkflowException {
  public WorkflowServiceException(
      @Nonnull WorkflowExecution execution, String workflowType, Throwable cause) {
    super(execution, workflowType, cause);
  }
}
