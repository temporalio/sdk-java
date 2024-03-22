package io.temporal.worker.slotsupplier;

public class SlotReservationData implements SlotReservationContext {
  private final String taskQueue;
  private final boolean sticky;

  public SlotReservationData(String taskQueue, boolean sticky) {
    this.taskQueue = taskQueue;
    this.sticky = sticky;
  }

  @Override
  public String getTaskQueue() {
    return taskQueue;
  }

  @Override
  public boolean isSticky() {
    return sticky;
  }
}
