package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;

/**
 * Thrown when a workflow with the given id is not known to the Temporal service or in an incorrect
 * state to perform the operation.
 *
 * <p>Examples of possible causes:
 *
 * <ul>
 *   <li>workflow id doesn't exist
 *   <li>workflow was purged from the service after reaching its retention limit
 *   <li>attempt to signal a workflow that is completed
 * </ul>
 */
public final class WorkflowNotFoundException extends WorkflowException {

  public WorkflowNotFoundException(
      WorkflowExecution execution, String workflowType, Throwable cause) {
    super(execution, workflowType, cause);
  }
}
