package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;

/**
 * Error that occurs when an update call times out or is cancelled.
 *
 * <p>Note, this is not related to any general concept of timing out or cancelling a running update,
 * this is only related to the client call itself.
 */
public class WorkflowUpdateTimeoutOrCancelledException extends WorkflowServiceException {
  public WorkflowUpdateTimeoutOrCancelledException(
      WorkflowExecution execution, String updateId, String updateName, Throwable cause) {
    super(execution, "", cause);
  }
}
