package io.temporal.internal.worker;

import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.failure.v1.Failure;

/**
 * Internal. Do not throw or catch in application level code.
 *
 * <p>This exception is used to signal that the workflow execution should be failed with {@link
 * CommandType#COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION}
 */
public final class WorkflowExecutionException extends RuntimeException {
  private final Failure failure;

  public WorkflowExecutionException(Failure failure) {
    super(failure.getMessage());
    this.failure = failure;
  }

  public Failure getFailure() {
    return failure;
  }
}
