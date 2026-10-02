package io.temporal.internal.payload.storage;

import io.temporal.common.CancellationToken;
import io.temporal.internal.common.AsyncSemaphore;
import io.temporal.payload.storage.StorageDriverLimiter;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** The storage limits applied while one message is processed. */
final class MessageStorageLimits {
  /** Limits operations for this message alone. */
  private final AsyncSemaphore perMessageSemaphore;

  /** Limits operations across every message using the same ExternalStorage. */
  private final AsyncSemaphore perInstanceSemaphore;

  MessageStorageLimits(int maxOperationsPerMessage, AsyncSemaphore perInstanceSemaphore) {
    this.perMessageSemaphore = new AsyncSemaphore(maxOperationsPerMessage);
    this.perInstanceSemaphore = perInstanceSemaphore;
  }

  /** Returns a limiter for a single driver call. */
  <I> UsageTrackingLimiter<I> newLimiter(
      CancellationToken<CancellationException> cancellationToken) {
    return new UsageTrackingLimiter<>(perMessageSemaphore, perInstanceSemaphore, cancellationToken);
  }

  /** Applies the storage limits to a single driver call. */
  static final class UsageTrackingLimiter<I> implements StorageDriverLimiter<I> {
    private final AsyncSemaphore perMessageSemaphore;
    private final AsyncSemaphore perInstanceSemaphore;
    private final CancellationToken<CancellationException> cancellationToken;

    /** Set when the driver takes its first permit. */
    private final AtomicBoolean permitTaken = new AtomicBoolean();

    private UsageTrackingLimiter(
        AsyncSemaphore perMessageSemaphore,
        AsyncSemaphore perInstanceSemaphore,
        CancellationToken<CancellationException> cancellationToken) {
      this.perMessageSemaphore = perMessageSemaphore;
      this.perInstanceSemaphore = perInstanceSemaphore;
      this.cancellationToken = cancellationToken;
    }

    @Override
    public <T> CompletableFuture<T> permit(I item, Supplier<CompletableFuture<T>> operation) {
      permitTaken.set(true);
      return perMessageSemaphore
          .acquire()
          .thenCompose(
              ignored ->
                  perInstanceSemaphore
                      .acquire()
                      .thenCompose(
                          alsoIgnored -> {
                            cancellationToken.throwIfCancellationRequested();
                            return operation.get();
                          })
                      .whenComplete((result, error) -> perInstanceSemaphore.release()))
          .whenComplete((result, error) -> perMessageSemaphore.release());
    }

    /** True if the driver took at least one permit. */
    boolean tookPermit() {
      return permitTaken.get();
    }
  }
}
