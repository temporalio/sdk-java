package io.temporal.worker.tuning;

import com.google.common.base.Preconditions;
import io.temporal.internal.common.AsyncSemaphore;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * This implementation of {@link SlotSupplier} provides a fixed number of slots backed by a
 * semaphore, and is the default behavior when a custom supplier is not explicitly specified.
 *
 * @param <SI> The slot info type for this supplier.
 */
public class FixedSizeSlotSupplier<SI extends SlotInfo> implements SlotSupplier<SI> {
  private final int numSlots;
  private final AsyncSemaphore executorSlotsSemaphore;

  public FixedSizeSlotSupplier(int numSlots) {
    Preconditions.checkArgument(numSlots > 0, "FixedSizeSlotSupplier must have at least one slot");
    this.numSlots = numSlots;
    executorSlotsSemaphore = new AsyncSemaphore(numSlots);
  }

  @Override
  public SlotSupplierFuture reserveSlot(SlotReserveContext<SI> ctx) throws Exception {
    CompletableFuture<Void> slotFuture = executorSlotsSemaphore.acquire();
    return SlotSupplierFuture.fromCompletableFuture(
        slotFuture.thenApply(ignored -> new SlotPermit()), () -> slotFuture.cancel(true));
  }

  @Override
  public Optional<SlotPermit> tryReserveSlot(SlotReserveContext<SI> ctx) {
    boolean gotOne = executorSlotsSemaphore.tryAcquire();
    if (gotOne) {
      return Optional.of(new SlotPermit());
    }
    return Optional.empty();
  }

  @Override
  public void markSlotUsed(SlotMarkUsedContext<SI> ctx) {}

  @Override
  public void releaseSlot(SlotReleaseContext<SI> ctx) {
    executorSlotsSemaphore.release();
  }

  @Override
  public Optional<Integer> getMaximumSlots() {
    return Optional.of(numSlots);
  }

  @Override
  public String toString() {
    return "FixedSizeSlotSupplier{" + "numSlots=" + numSlots + '}';
  }
}
