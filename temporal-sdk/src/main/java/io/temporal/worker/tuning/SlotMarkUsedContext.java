package io.temporal.worker.tuning;

public interface SlotMarkUsedContext<SI extends SlotInfo> {
  /**
   * @return The information associated with the slot that is being marked as used.
   */
  SI getSlotInfo();

  /**
   * @return The previously reserved permit that is being used with this slot.
   */
  SlotPermit getSlotPermit();
}
