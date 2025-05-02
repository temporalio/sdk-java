package io.temporal.testUtils;

import io.temporal.worker.tuning.*;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CountingSlotSupplier<SI extends SlotInfo> extends FixedSizeSlotSupplier<SI> {
  public final AtomicInteger reservedCount = new AtomicInteger();
  public final AtomicInteger releasedCount = new AtomicInteger();
  public final AtomicInteger usedCount = new AtomicInteger();
  public final ConcurrentHashMap.KeySetView<SlotPermit, Boolean> currentUsedSet =
      ConcurrentHashMap.newKeySet();

  public CountingSlotSupplier(int numSlots) {
    super(numSlots);
  }

  @Override
  public SlotSupplierFuture reserveSlot(SlotReserveContext<SI> ctx) throws Exception {
    SlotSupplierFuture p = super.reserveSlot(ctx);
    p.thenRun(reservedCount::incrementAndGet);
    return p;
  }

  @Override
  public Optional<SlotPermit> tryReserveSlot(SlotReserveContext<SI> ctx) {
    Optional<SlotPermit> p = super.tryReserveSlot(ctx);
    if (p.isPresent()) {
      reservedCount.incrementAndGet();
    }
    return p;
  }

  @Override
  public void markSlotUsed(SlotMarkUsedContext<SI> ctx) {
    usedCount.incrementAndGet();
    currentUsedSet.add(ctx.getSlotPermit());
    super.markSlotUsed(ctx);
  }

  @Override
  public void releaseSlot(SlotReleaseContext<SI> ctx) {
    super.releaseSlot(ctx);
    currentUsedSet.remove(ctx.getSlotPermit());
    releasedCount.incrementAndGet();
  }
}
