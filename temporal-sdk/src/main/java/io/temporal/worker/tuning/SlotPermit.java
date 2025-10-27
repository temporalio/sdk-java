package io.temporal.worker.tuning;

/**
 * This class is handed out by implementations of {@link SlotSupplier}. Permits are held until the
 * tasks they are associated with (if any) are finished processing, or if the reservation is no
 * longer needed. Your supplier implementation may store additional data in the permit, if desired.
 *
 * <p>When {@link SlotSupplier#releaseSlot(SlotReleaseContext)} is called, the exact same instance
 * of the permit is passed back to the supplier.
 */
public final class SlotPermit {
  public final Object userData;

  public SlotPermit() {
    this.userData = null;
  }

  public SlotPermit(Object userData) {
    this.userData = userData;
  }
}
