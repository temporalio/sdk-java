package io.temporal.internal.worker;

public class SlotReservationData {
  public final String taskQueue;
  public final String workerIdentity;
  public final String workerBuildId;

  public SlotReservationData(String taskQueue, String workerIdentity, String workerBuildId) {
    this.taskQueue = taskQueue;
    this.workerIdentity = workerIdentity;
    this.workerBuildId = workerBuildId;
  }
}
