package io.temporal.worker.slotsupplier;

public interface SlotReservationContext {
  /*
   * @return the task queue for which this reservation request is associated.
   */
  String getTaskQueue();

  /*
   * @return true if the reservation request is for polling on a sticky workflow task queue.
   */
  boolean isSticky();
}
