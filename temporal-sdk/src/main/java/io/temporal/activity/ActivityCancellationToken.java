package io.temporal.activity;

import io.temporal.client.ActivityCanceledException;
import io.temporal.common.Experimental;
import java.util.concurrent.CompletableFuture;

/** Token that allows an Activity implementation to observe cancellation requests. */
@Experimental
public interface ActivityCancellationToken {

  ActivityCancellationToken NONE =
      new ActivityCancellationToken() {
        @Override
        public boolean isCancellationRequested() {
          return false;
        }

        @Override
        public void throwIfCancellationRequested() throws ActivityCanceledException {}

        @Override
        public CompletableFuture<Void> getCancellationFuture() {
          return new CompletableFuture<>();
        }
      };

  /**
   * Returns true after cancellation has been requested for this Activity Execution.
   *
   * <p>If this method returns true, the Activity implementation should stop its work and usually
   * call {@link #throwIfCancellationRequested()} to report successful cancellation to Temporal.
   */
  boolean isCancellationRequested();

  /**
   * Throws {@link ActivityCanceledException} if cancellation has been requested for this Activity
   * Execution.
   *
   * <p>Rethrowing this exception from Activity code reports successful cancellation to Temporal.
   */
  void throwIfCancellationRequested() throws ActivityCanceledException;

  /**
   * Future that completes exceptionally with {@link ActivityCanceledException} when cancellation
   * has been requested for this Activity Execution.
   *
   * <p>Activity code should still call {@link #throwIfCancellationRequested()} or otherwise report
   * cancellation if it wants the Activity Execution to complete as canceled.
   */
  CompletableFuture<Void> getCancellationFuture();
}
