package io.temporal.worker.slotsupplier;

public class LocalActivitySlotInfo {
  private final String activityType;

  public LocalActivitySlotInfo(String workflowType) {
    this.activityType = workflowType;
  }

  public String getActivityType() {
    return activityType;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    LocalActivitySlotInfo that = (LocalActivitySlotInfo) o;

    return activityType.equals(that.activityType);
  }

  @Override
  public int hashCode() {
    return activityType.hashCode();
  }

  @Override
  public String toString() {
    return "WorkflowSlotInfo{" + "activityType='" + activityType + "'}";
  }
}
