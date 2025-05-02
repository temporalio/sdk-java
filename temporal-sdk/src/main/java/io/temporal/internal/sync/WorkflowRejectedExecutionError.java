package io.temporal.internal.sync;

public class WorkflowRejectedExecutionError extends Error {

  WorkflowRejectedExecutionError(Throwable cause) {
    super(cause);
  }
}
