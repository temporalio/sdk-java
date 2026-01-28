package io.temporal.worker.tuning;

import javax.annotation.Nullable;

public interface SlotReleaseContext<SI extends SlotInfo> {
  /**
   * @return The reason the slot is being released.
   */
  SlotReleaseReason getSlotReleaseReason();

  /**
   * @return The permit the slot was using that is now being released.
   */
  SlotPermit getSlotPermit();

  /**
   * @return The information associated with the slot that is being released. May be null if the
   *     slot was never marked as used.
   */
  @Nullable
  SI getSlotInfo();
}
