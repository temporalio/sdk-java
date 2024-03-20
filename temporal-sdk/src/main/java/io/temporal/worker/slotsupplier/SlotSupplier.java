package io.temporal.worker.slotsupplier;

import java.util.Optional;

public interface SlotSupplier<SlotInfo> {
  // TODO: Needs to be cancellable
  SlotPermit reserveSlot(SlotReservationContext ctx) throws InterruptedException;

  Optional<SlotPermit> tryReserveSlot(SlotReservationContext ctx);

  void markSlotUsed(SlotInfo info, SlotPermit permit);

  void releaseSlot(SlotReleaseReason reason, SlotPermit permit);
}
