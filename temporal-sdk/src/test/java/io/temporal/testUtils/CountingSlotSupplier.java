package io.temporal.testUtils;

import io.temporal.worker.tuning.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class CountingSlotSupplier<SI extends SlotInfo> extends FixedSizeSlotSupplier<SI> {
  public final AtomicInteger reservedCount = new AtomicInteger();
  public final AtomicInteger releasedCount = new AtomicInteger();

  public CountingSlotSupplier(int numSlots) {
    super(numSlots);
  }

  @Override
  public SlotPermit reserveSlot(SlotReserveContext<SI> ctx) throws InterruptedException {
    SlotPermit p = super.reserveSlot(ctx);
    reservedCount.incrementAndGet();
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
  public void releaseSlot(SlotReleaseContext<SI> ctx) {
    super.releaseSlot(ctx);
    releasedCount.incrementAndGet();
  }
}
