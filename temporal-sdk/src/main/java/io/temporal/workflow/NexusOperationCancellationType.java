package io.temporal.workflow;

import io.temporal.failure.CanceledFailure;

/**
 * Defines behavior of the parent workflow when {@link CancellationScope} that wraps Nexus operation
 * is canceled. The result of the cancellation independently of the type is a {@link
 * CanceledFailure} thrown from the Nexus operation method.
 */
public enum NexusOperationCancellationType {
  /** Wait for operation completion. Operation may or may not complete as cancelled. Default. */
  WAIT_COMPLETED,

  /**
   * Request cancellation of the operation and wait for confirmation that the request was received.
   * Doesn't wait for actual cancellation.
   */
  WAIT_REQUESTED,

  /**
   * Initiate a cancellation request and immediately report cancellation to the caller. Note that it
   * doesn't guarantee that cancellation is delivered to the operation handler if the caller exits
   * before the delivery is done.
   */
  TRY_CANCEL,

  /** Do not request cancellation of the operation. */
  ABANDON,
}
