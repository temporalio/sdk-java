package io.temporal.worker.slotsupplier;

public class ActivitySlotInfo {
  private final String activityTypeName;

  public ActivitySlotInfo(String workflowType) {
    this.activityTypeName = workflowType;
  }

  public String getActivityTypeName() {
    return activityTypeName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ActivitySlotInfo that = (ActivitySlotInfo) o;

    return activityTypeName.equals(that.activityTypeName);
  }

  @Override
  public int hashCode() {
    return activityTypeName.hashCode();
  }

  @Override
  public String toString() {
    return "WorkflowSlotInfo{" + "activityType='" + activityTypeName + "'}";
  }
}
