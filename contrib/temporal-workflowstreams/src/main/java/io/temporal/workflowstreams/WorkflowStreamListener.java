package io.temporal.workflowstreams;

import io.temporal.common.Experimental;
import java.util.concurrent.CompletionStage;
import org.slf4j.LoggerFactory;

/**
 * Receives items from a workflow stream subscription without occupying a caller thread. Pass one to
 * {@link WorkflowStreamClient#subscribe(SubscribeOptions, WorkflowStreamListener)}; the returned
 * {@link WorkflowStreamSubscriptionHandle} stops the subscription.
 *
 * <p>Callbacks are serialized (never invoked concurrently, with happens-before ordering between
 * invocations) and run on the client's poll executor, so they must not block; to defer further
 * delivery, return a pending stage from {@link #onNext}.
 */
@Experimental
public interface WorkflowStreamListener {
  /**
   * Called with the next item on the stream. Return {@code null} or an already-completed stage to
   * receive the next item immediately; return a pending stage to defer both further delivery and
   * the next poll until it completes (backpressure). A stage that completes exceptionally — or an
   * exception thrown directly — stops the subscription and is reported to {@link #onError}.
   */
  CompletionStage<Void> onNext(WorkflowStreamItem item);

  /**
   * Called once when the subscription stops because of an unrecoverable failure (including a
   * failure from {@link #onNext}). No further callbacks follow. The default implementation logs the
   * failure at warn level.
   */
  default void onError(Throwable failure) {
    LoggerFactory.getLogger(WorkflowStreamListener.class)
        .warn("workflowstreams: subscription failed", failure);
  }

  /**
   * Called once when the stream ends cleanly because the workflow reached a terminal state. Not
   * called when the subscription is stopped via {@link WorkflowStreamSubscriptionHandle#close}. No
   * further callbacks follow.
   */
  default void onCompleted() {}
}
