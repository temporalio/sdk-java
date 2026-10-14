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

  /** Limits operations across every message run by the owning client or worker. */
  private final AsyncSemaphore driverOperationSemaphore;

  /** The configured per message operation limit. */
  private final int maxOperationsPerMessage;

  MessageStorageLimits(int maxOperationsPerMessage, AsyncSemaphore driverOperationSemaphore) {
    this.perMessageSemaphore = new AsyncSemaphore(maxOperationsPerMessage);
    this.driverOperationSemaphore = driverOperationSemaphore;
    this.maxOperationsPerMessage = maxOperationsPerMessage;
  }

  /** Returns the configured per message operation limit. */
  int maxOperationsPerMessage() {
    return maxOperationsPerMessage;
  }

  /** Returns a limiter for a single driver call. */
  <I> UsageTrackingLimiter<I> newLimiter(
      CancellationToken<CancellationException> cancellationToken) {
    return new UsageTrackingLimiter<>(
        perMessageSemaphore, driverOperationSemaphore, cancellationToken);
  }

  /** Applies the storage limits to a single driver call. */
  static final class UsageTrackingLimiter<I> implements StorageDriverLimiter<I> {
    private final AsyncSemaphore perMessageSemaphore;
    private final AsyncSemaphore driverOperationSemaphore;
    private final CancellationToken<CancellationException> cancellationToken;

    /** Set when the driver takes its first permit. */
    private final AtomicBoolean permitTaken = new AtomicBoolean();

    private UsageTrackingLimiter(
        AsyncSemaphore perMessageSemaphore,
        AsyncSemaphore driverOperationSemaphore,
        CancellationToken<CancellationException> cancellationToken) {
      this.perMessageSemaphore = perMessageSemaphore;
      this.driverOperationSemaphore = driverOperationSemaphore;
      this.cancellationToken = cancellationToken;
    }

    @Override
    public <T> CompletableFuture<T> permit(I item, Supplier<CompletableFuture<T>> operation) {
      permitTaken.set(true);
      return perMessageSemaphore
          .acquire()
          .thenCompose(
              ignored ->
                  driverOperationSemaphore
                      .acquire()
                      .thenCompose(
                          alsoIgnored -> {
                            cancellationToken.throwIfCancellationRequested();
                            return operation.get();
                          })
                      .whenComplete((result, error) -> driverOperationSemaphore.release()))
          .whenComplete((result, error) -> perMessageSemaphore.release());
    }

    /** True if the driver took at least one permit. */
    boolean tookPermit() {
      return permitTaken.get();
    }
  }
}
