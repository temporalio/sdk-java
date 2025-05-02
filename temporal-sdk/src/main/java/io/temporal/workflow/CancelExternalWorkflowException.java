package io.temporal.workflow;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowException;

/**
 * Exception used to communicate failure of a request to cancel an external workflow.
 *
 * <p>TODO: Hook it up with RequestCancelExternalWorkflowExecutionFailed and
 * WorkflowExecutionCancelRequested
 */
@SuppressWarnings("serial")
public final class CancelExternalWorkflowException extends WorkflowException {

  public CancelExternalWorkflowException(
      String message, WorkflowExecution execution, String workflowType, Throwable cause) {
    super(message, execution, workflowType, cause);
  }

  @Override
  public String toString() {
    return "CancelExternalWorkflowException{"
        + "execution="
        + getExecution()
        + ", workflowType='"
        + getWorkflowType()
        + '\''
        + '}';
  }
}
