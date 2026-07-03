package io.temporal.internal.concurrent.structured;

import io.temporal.common.CancellationToken;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Internal task handle over a {@link CompletableFuture} that manages cancellation and tracks
 * derived tasks.
 *
 * <p><strong>Continuation style.</strong> {@link #map}, {@link #recover}, and {@link #whenSettled}
 * chain follow-on work, mirroring {@code thenApply}/{@code exceptionally}/{@code whenComplete}.
 *
 * <p><strong>Cancellation is downstream by default.</strong> {@link #cancel()} settles this task
 * and every task derived from it (its {@code map}/{@code recover} children). It does <em>not</em>
 * cancel the task this one was derived <em>from</em>.
 *
 * @param <T> the result type
 */
interface AsyncTask<T> extends TaskChain<T> {

  @Override
  <R> AsyncTask<R> map(Function<? super T, ? extends R> fn);

  @Override
  AsyncTask<T> recover(Function<? super Throwable, ? extends T> fn);

  @Override
  default AsyncTask<Void> thenAccept(Consumer<? super T> fn) {
    return map(
        value -> {
          fn.accept(value);
          return null;
        });
  }

  /**
   * Runs a side effect when this task settles. Unlike {@code CompletableFuture.whenComplete}, the
   * callback receives the unwrapped throwable ({@code null} on success).
   */
  AsyncTask<T> whenSettled(BiConsumer<? super T, ? super Throwable> cb);

  /**
   * Cancels this task and everything derived from it.
   *
   * @return {@code true} if this call initiated cancellation (the task had not already settled).
   */
  boolean cancel();

  boolean isDone();

  boolean isCancelled();

  /**
   * Blocks for the value; throws on failure, or {@link java.util.concurrent.CancellationException}
   * on cancel.
   */
  T join();

  /** Blocks until settled and returns the outcome as a {@link Result}; never throws. */
  Result<T> joinSettled();

  /**
   * @return the read-only cancellation token for this task.
   */
  CancellationToken token();

  /** Escape hatch to the underlying future for interop with existing APIs. */
  CompletableFuture<T> toCompletableFuture();
}
