package io.temporal.client.schedules;

import java.util.Objects;

/** Details for a listed schedule. */
public final class ScheduleListSchedule {
  private final ScheduleListAction action;
  private final ScheduleSpec spec;
  private final ScheduleListState state;

  public ScheduleListSchedule(
      ScheduleListAction action, ScheduleSpec spec, ScheduleListState state) {
    this.action = action;
    this.spec = spec;
    this.state = state;
  }

  /**
   * Get the action taken when scheduled.
   *
   * @return Action taken when scheduled
   */
  public ScheduleListAction getAction() {
    return action;
  }

  /**
   * Get when the action is taken.
   *
   * @return When the action is taken
   */
  public ScheduleSpec getSpec() {
    return spec;
  }

  /**
   * Get the state of the schedule.
   *
   * @return State of the schedule.
   */
  public ScheduleListState getState() {
    return state;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleListSchedule that = (ScheduleListSchedule) o;
    return Objects.equals(action, that.action)
        && Objects.equals(spec, that.spec)
        && Objects.equals(state, that.state);
  }

  @Override
  public int hashCode() {
    return Objects.hash(action, spec, state);
  }

  @Override
  public String toString() {
    return "ScheduleListSchedule{"
        + "action="
        + action
        + ", spec="
        + spec
        + ", state="
        + state
        + '}';
  }
}
