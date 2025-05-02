package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;

/** Exception used to communicate failure of an update workflow execution request. */
public final class WorkflowUpdateException extends WorkflowException {

  private final String updateId;
  private final String updateName;

  public WorkflowUpdateException(
      WorkflowExecution execution, String updateId, String updateName, Throwable cause) {
    super(execution, null, cause);
    this.updateName = updateName;
    this.updateId = updateId;
  }

  public String GetUpdateId() {
    return updateId;
  }

  public String GetUpdateName() {
    return updateName;
  }
}
