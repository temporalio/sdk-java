package io.temporal.workflow;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowException;

/** Exception used to communicate failure of a request to signal an external workflow. */
public final class SignalExternalWorkflowException extends WorkflowException {

  public SignalExternalWorkflowException(WorkflowExecution execution, String workflowType) {
    super(getMessage(execution, workflowType), execution, workflowType, null);
  }

  public static String getMessage(WorkflowExecution execution, String workflowType) {
    return "message='Open execution not found', workflowId='"
        + execution.getWorkflowId()
        + "', runId='"
        + execution.getRunId()
        + "'"
        + (workflowType == null ? "" : "', workflowType='" + workflowType + '\'');
  }
}
