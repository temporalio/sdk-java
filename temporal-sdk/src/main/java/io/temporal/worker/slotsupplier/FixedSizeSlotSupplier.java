package io.temporal.worker.slotsupplier;

import java.util.Optional;
import java.util.concurrent.Semaphore;

public class FixedSizeSlotSupplier<SlotInfo> implements SlotSupplier<SlotInfo> {
  private final Semaphore executorSlotsSemaphore;

  public FixedSizeSlotSupplier(int numSlots) {
    executorSlotsSemaphore = new Semaphore(numSlots);
  }

  @Override
  public SlotPermit reserveSlot(SlotReservationContext ctx) throws InterruptedException {
    executorSlotsSemaphore.acquire();
    return new SlotPermit();
  }

  @Override
  public Optional<SlotPermit> tryReserveSlot(SlotReservationContext ctx) {
    boolean gotOne = executorSlotsSemaphore.tryAcquire();
    if (gotOne) {
      return Optional.of(new SlotPermit());
    }
    return Optional.empty();
  }

  @Override
  public void markSlotUsed(SlotInfo slotInfo, SlotPermit permit) {}

  @Override
  public void releaseSlot(SlotReleaseReason reason, SlotPermit permit) {}
}
