package io.temporal.worker.tuning;

import io.temporal.common.Experimental;
import java.util.Objects;

/** Contains information about a slot that is being used to execute a nexus task. */
@Experimental
public class NexusSlotInfo extends SlotInfo {
  private final String service;
  private final String operation;
  private final String taskQueue;
  private final String workerIdentity;
  private final String workerBuildId;

  public NexusSlotInfo(
      String service,
      String operation,
      String taskQueue,
      String workerIdentity,
      String workerBuildId) {
    this.service = service;
    this.operation = operation;
    this.taskQueue = taskQueue;
    this.workerIdentity = workerIdentity;
    this.workerBuildId = workerBuildId;
  }

  public String getService() {
    return service;
  }

  public String getOperation() {
    return operation;
  }

  public String getTaskQueue() {
    return taskQueue;
  }

  public String getWorkerIdentity() {
    return workerIdentity;
  }

  public String getWorkerBuildId() {
    return workerBuildId;
  }

  @Override
  public String toString() {
    return "NexusSlotInfo{"
        + "service='"
        + service
        + '\''
        + ", operation='"
        + operation
        + '\''
        + ", taskQueue='"
        + taskQueue
        + '\''
        + ", workerIdentity='"
        + workerIdentity
        + '\''
        + ", workerBuildId='"
        + workerBuildId
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    NexusSlotInfo that = (NexusSlotInfo) o;
    return Objects.equals(service, that.service)
        && Objects.equals(operation, that.operation)
        && Objects.equals(taskQueue, that.taskQueue)
        && Objects.equals(workerIdentity, that.workerIdentity)
        && Objects.equals(workerBuildId, that.workerBuildId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(service, operation, taskQueue, workerIdentity, workerBuildId);
  }
}
