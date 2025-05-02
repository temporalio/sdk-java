package io.temporal.internal.replay;

import io.temporal.api.failure.v1.Failure;
import io.temporal.failure.ChildWorkflowFailure;

/**
 * Internal. Do not catch or throw by application level code. Used by the child workflow state
 * machines in case of child workflow task execution failure and contains an original unparsed
 * Failure message with details from the attributes in the exception.
 *
 * <p>This class is needed to don't make Failure -&gt; Exception conversion inside the state
 * machines. So the state machine forms ChildWorkflowFailure without cause and parse the original
 * Failure, so the outside code may join them together.
 */
public class ChildWorkflowTaskFailedException extends RuntimeException {

  private final ChildWorkflowFailure exception;

  private final Failure originalCauseFailure;

  public ChildWorkflowTaskFailedException(
      ChildWorkflowFailure exception, Failure originalCauseFailure) {
    this.exception = exception;
    this.originalCauseFailure = originalCauseFailure;
  }

  public ChildWorkflowFailure getException() {
    return exception;
  }

  public Failure getOriginalCauseFailure() {
    return originalCauseFailure;
  }
}
