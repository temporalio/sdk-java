package io.temporal.client.schedules;

import java.util.Objects;

/** Action execution representing a scheduled workflow start. */
public final class ScheduleActionExecutionStartWorkflow extends ScheduleActionExecution {
  private final String workflowId;
  private final String firstExecutionRunId;

  public ScheduleActionExecutionStartWorkflow(String workflowId, String firstExecutionRunId) {
    this.workflowId = workflowId;
    this.firstExecutionRunId = firstExecutionRunId;
  }

  /**
   * Get the workflow ID of the scheduled workflow.
   *
   * @return workflow ID
   */
  public String getWorkflowId() {
    return workflowId;
  }

  /**
   * Get the workflow run ID of the scheduled workflow.
   *
   * @return run ID
   */
  public String getFirstExecutionRunId() {
    return firstExecutionRunId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleActionExecutionStartWorkflow that = (ScheduleActionExecutionStartWorkflow) o;
    return Objects.equals(workflowId, that.workflowId)
        && Objects.equals(firstExecutionRunId, that.firstExecutionRunId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(workflowId, firstExecutionRunId);
  }

  @Override
  public String toString() {
    return "ScheduleActionExecutionStartWorkflow{"
        + "workflowId='"
        + workflowId
        + '\''
        + ", firstExecutionRunId='"
        + firstExecutionRunId
        + '\''
        + '}';
  }
}
