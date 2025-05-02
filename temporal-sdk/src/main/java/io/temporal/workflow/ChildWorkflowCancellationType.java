package io.temporal.workflow;

import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.failure.CanceledFailure;

/**
 * Defines behaviour of the parent workflow when {@link CancellationScope} that wraps child workflow
 * execution request is canceled. The result of the cancellation independently of the type is a
 * {@link CanceledFailure} thrown from the child workflow method.
 */
public enum ChildWorkflowCancellationType {
  /** Wait for child cancellation completion. */
  WAIT_CANCELLATION_COMPLETED,

  /**
   * Request cancellation of the child and wait for confirmation that the request was received.
   * Doesn't wait for actual cancellation.
   */
  WAIT_CANCELLATION_REQUESTED,

  /**
   * Initiate a cancellation request and immediately report cancellation to the parent. Note that it
   * doesn't guarantee that cancellation is delivered to the child if parent exits before the
   * delivery is done. It can be mitigated by setting {@link ParentClosePolicy} to {@link
   * ParentClosePolicy#PARENT_CLOSE_POLICY_REQUEST_CANCEL}.
   */
  TRY_CANCEL,

  /** Do not request cancellation of the child workflow */
  ABANDON,
}
