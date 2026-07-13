package io.temporal.workflowstreams;

import io.temporal.common.Experimental;
import java.util.concurrent.CompletableFuture;

/**
 * Controls a listener-based subscription started with {@link
 * WorkflowStreamClient#subscribe(SubscribeOptions, WorkflowStreamListener)}.
 */
@Experimental
public interface WorkflowStreamSubscriptionHandle extends AutoCloseable {
  /**
   * Stops the subscription before the next poll; a poll already blocked on the server is not
   * interrupted, and its result is discarded. Idempotent. Does not trigger {@link
   * WorkflowStreamListener#onCompleted}.
   */
  @Override
  void close();

  /**
   * Returns a future that tracks the end of the subscription: it completes normally when the stream
   * ends cleanly (after {@link WorkflowStreamListener#onCompleted}) or the subscription is closed,
   * and completes exceptionally with the failure passed to {@link WorkflowStreamListener#onError}.
   */
  CompletableFuture<Void> getDoneFuture();
}
