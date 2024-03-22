package io.temporal.worker.slotsupplier;

import java.util.Optional;

public interface SlotSupplier<SlotInfo> {
  // TODO: Needs to be cancellable
  SlotPermit reserveSlot(SlotReservationContext ctx) throws InterruptedException;

  Optional<SlotPermit> tryReserveSlot(SlotReservationContext ctx);

  void markSlotUsed(SlotInfo info, SlotPermit permit);

  void releaseSlot(SlotReleaseReason reason, SlotPermit permit);

  /**
   * Because we currently use thread pools to execute tasks, there must be *some* defined
   * upper-limit on the size of the thread pool for each kind of task.
   *
   * <p>This method may be deprecated and removed later if and when the SDK supports virtual
   * threads.
   *
   * @return the maximum number of slots that can ever be in use at one type for this slot type.
   */
  int maximumSlots();
}
