package io.temporal.client.schedules;

import java.util.Objects;

/** Action to start a workflow on a listed schedule. */
public final class ScheduleListActionStartWorkflow extends ScheduleListAction {
  private final String workflow;

  public ScheduleListActionStartWorkflow(String workflow) {
    this.workflow = workflow;
  }

  /**
   * Get the scheduled workflow type name.
   *
   * @return Workflow type name
   */
  public String getWorkflow() {
    return workflow;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleListActionStartWorkflow that = (ScheduleListActionStartWorkflow) o;
    return Objects.equals(workflow, that.workflow);
  }

  @Override
  public int hashCode() {
    return Objects.hash(workflow);
  }

  @Override
  public String toString() {
    return "ScheduleListActionStartWorkflow{" + "workflow='" + workflow + '\'' + '}';
  }
}
