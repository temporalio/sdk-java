package io.temporal.internal.statemachines;

/**
 * Originated by Temporal State Machines and happens during application of the events inside
 * #handleEvent call.
 */
final class InternalWorkflowTaskException extends RuntimeException {

  public InternalWorkflowTaskException(String message, Throwable cause) {
    super(message, cause);
  }

  public InternalWorkflowTaskException(Throwable cause) {
    super(cause);
  }
}
