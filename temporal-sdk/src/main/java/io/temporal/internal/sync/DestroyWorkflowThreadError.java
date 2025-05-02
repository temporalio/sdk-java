package io.temporal.internal.sync;

/**
 * Used to interrupt deterministic thread execution. Assumption is that none of the code that thread
 * executes catches it.
 */
public final class DestroyWorkflowThreadError extends Error {

  DestroyWorkflowThreadError() {}

  DestroyWorkflowThreadError(String message) {
    super(message);
  }
}
