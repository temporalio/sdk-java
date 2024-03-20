package io.temporal.worker.slotsupplier;

public class WorkflowSlotInfo {
  private final String workflowType;

  public WorkflowSlotInfo(String workflowType) {
    this.workflowType = workflowType;
  }

  public String getWorkflowType() {
    return workflowType;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    WorkflowSlotInfo that = (WorkflowSlotInfo) o;

    return workflowType.equals(that.workflowType);
  }

  @Override
  public int hashCode() {
    return workflowType.hashCode();
  }

  @Override
  public String toString() {
    return "WorkflowSlotInfo{" + "workflowType='" + workflowType + "'}";
  }
}
