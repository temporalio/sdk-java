package io.temporal.common;

import java.util.concurrent.CompletableFuture;

/**
 * Token that allows asynchronous code to observe cancellation requests.
 *
 * @param <E> the exception type surfaced by {@link #throwIfCancellationRequested()} and {@link
 *     #getCancellationFuture()} when cancellation is requested
 */
@Experimental
public interface CancellationToken<E extends RuntimeException> {

  /** Returns true after cancellation has been requested. */
  boolean isCancellationRequested();

  /** Throws {@code E} if cancellation has been requested. */
  void throwIfCancellationRequested() throws E;

  /**
   * Future that completes exceptionally with {@code E} when cancellation has been requested.
   *
   * <p>Code waiting on external work can chain off this future to abort in-flight requests when
   * cancellation is requested.
   */
  default CompletableFuture<Void> getCancellationFuture() {
    CompletableFuture<Void> result = new CompletableFuture<>();
    Registration registration =
        onCancel(
            () -> {
              try {
                throwIfCancellationRequested();
              } catch (RuntimeException e) {
                result.completeExceptionally(e);
              }
            });
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

  /** A token that is never cancelled. */
  static <E extends RuntimeException> CancellationToken<E> none() {
    return new CancellationToken<E>() {
      @Override
      public boolean isCancellationRequested() {
        return false;
      }

      @Override
      public void throwIfCancellationRequested() {}

      @Override
      public CompletableFuture<Void> getCancellationFuture() {
        return new CompletableFuture<>();
      }

      @Override
      public Registration onCancel(Runnable callback) {
        return () -> {};
      }
    };
  }
}
