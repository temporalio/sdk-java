package io.temporal.worker.slotsupplier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

// TODO: Also make pauseable?
public class TrackingSlotSupplier<SlotInfo> implements SlotSupplier<SlotInfo> {
  private final SlotSupplier<SlotInfo> inner;
  private final AtomicInteger issuedSlots = new AtomicInteger();
  private final Map<SlotPermit, SlotInfo> usedSlots = new HashMap<>();

  public TrackingSlotSupplier(SlotSupplier<SlotInfo> inner) {
    this.inner = inner;
  }

  @Override
  public SlotPermit reserveSlot(SlotReservationContext ctx) throws InterruptedException {
    SlotPermit p = inner.reserveSlot(ctx);
    issuedSlots.incrementAndGet();
    return p;
  }

  @Override
  public Optional<SlotPermit> tryReserveSlot(SlotReservationContext ctx) {
    Optional<SlotPermit> p = inner.tryReserveSlot(ctx);
    if (p.isPresent()) {
      issuedSlots.incrementAndGet();
    }
    return p;
  }

  @Override
  public void markSlotUsed(SlotInfo slotInfo, SlotPermit permit) {
    inner.markSlotUsed(slotInfo, permit);
    usedSlots.put(permit, slotInfo);
  }

  @Override
  public void releaseSlot(SlotReleaseReason reason, SlotPermit permit) {
    inner.releaseSlot(reason, permit);
    issuedSlots.decrementAndGet();
    usedSlots.remove(permit);
  }

  @Override
  public int maximumSlots() {
    return inner.maximumSlots();
  }

  public int getIssuedSlots() {
    return issuedSlots.get();
  }
}
