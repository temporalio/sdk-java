package io.temporal.payload.storage;

import io.temporal.common.Experimental;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Limits the concurrent external storage operations a driver performs. Drivers must wrap each
 * operation in {@link #permit}. Operations performed outside a permit are not limited. Do not take
 * a permit inside another permit.
 */
@Experimental
public interface StorageDriverLimiter<I> {

  /**
   * Runs {@code operation} once a permit is available. {@code item} is the payload being stored or
   * the claim being retrieved.
   */
  @Nonnull
  <T> CompletableFuture<T> permit(
      @Nonnull I item, @Nonnull Supplier<CompletableFuture<T>> operation);

  /** Returns a limiter that never blocks. */
  @Nonnull
  static <I> StorageDriverLimiter<I> noop() {
    return new StorageDriverLimiter<I>() {
      @Override
      public <T> CompletableFuture<T> permit(
          @Nonnull I item, @Nonnull Supplier<CompletableFuture<T>> operation) {
        return operation.get();
      }
    };
  }
}
