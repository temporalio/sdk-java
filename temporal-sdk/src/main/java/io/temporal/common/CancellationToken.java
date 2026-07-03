package io.temporal.common;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Token that allows asynchronous code to observe cancellation requests. */
@Experimental
public interface CancellationToken {
  CancellationToken NONE =
      new CancellationToken() {
        @Override
        public boolean isCancellationRequested() {
          return false;
        }

        @Override
        public void throwIfCancellationRequested() throws CancellationException {}

        @Override
        public Registration onCancel(Runnable callback) {
          return () -> {};
        }
      };

  /** Returns true after cancellation has been requested. */
  boolean isCancellationRequested();

  /** Throws {@link CancellationException} if cancellation has been requested. */
  void throwIfCancellationRequested() throws CancellationException;

  /**
   * Future that completes normally when cancellation has been requested.
   *
   * <p>Code waiting on external work can attach a callback to this future to abort in-flight
   * requests when cancellation is requested.
   */
  default CompletableFuture<Void> getCancellationFuture() {
    CompletableFuture<Void> result = new CompletableFuture<>();
    Registration registration = onCancel(() -> result.complete(null));
    result.whenComplete((ignored, error) -> registration.close());
    return result;
  }

  /**
   * Registers a callback to run when cancellation is requested, or immediately if already
   * cancelled.
   *
   * @return a handle that removes the callback if cancellation has not happened yet.
   */
  Registration onCancel(Runnable callback);

  /** Handle for removing a previously registered cancellation callback. */
  interface Registration extends AutoCloseable {
    @Override
    void close();
  }
}
