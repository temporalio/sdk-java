package io.temporal.worker;

/**
 * Thrown if history events from the server don't match commands issued by the execution or replay
 * of workflow code. <br>
 * This exception usually means that there is some form of non-determinism in workflow code that has
 * lead to a difference in the execution path taken upon replay when compared to initial execution.
 * That is to say the history the worker received for this workflow cannot be processed by the
 * current workflow code. If this happens during the replay of a new Workflow Task, this exception
 * will cause the Workflow Task to fail {@link
 * io.temporal.api.enums.v1.WorkflowTaskFailedCause#WORKFLOW_TASK_FAILED_CAUSE_NON_DETERMINISTIC_ERROR}
 */
public class NonDeterministicException extends IllegalStateException {
  public NonDeterministicException(String message, Throwable cause) {
    super("[TMPRL1100] " + message, cause);
  }

  public NonDeterministicException(String message) {
    super("[TMPRL1100] " + message);
  }
}
