package io.temporal.internal.concurrent.structured;

import java.util.function.Consumer;
import java.util.function.Function;

/** A scope-owned continuation chain. Results are observed through the owning {@link TaskScope}. */
public interface TaskChain<T> {

  /** Transforms the success value once available. Failures/cancellation pass through untouched. */
  <R> TaskChain<R> map(Function<? super T, ? extends R> fn);

  /**
   * Supplies a fallback value if this chain fails. Unlike {@code CompletableFuture.exceptionally}, a
   * cancelled chain is not recovered (cancellation propagates) and the fallback receives the
   * unwrapped cause.
   */
  TaskChain<T> recover(Function<? super Throwable, ? extends T> fn);

  /** Runs a side effect on success and yields a {@code Void} chain. */
  default TaskChain<Void> thenAccept(Consumer<? super T> fn) {
    return map(
        value -> {
          fn.accept(value);
          return null;
        });
  }
}
