package io.temporal.client.schedules;

import java.time.Instant;
import java.util.Objects;

/** Information about when an action took place. */
public final class ScheduleActionResult {
  private final Instant scheduledAt;
  private final Instant startedAt;
  private final ScheduleActionExecution action;

  public ScheduleActionResult(
      Instant scheduledAt, Instant startedAt, ScheduleActionExecution action) {
    this.scheduledAt = scheduledAt;
    this.startedAt = startedAt;
    this.action = action;
  }

  /**
   * Get the scheduled time of the action including jitter.
   *
   * @return scheduled time of action
   */
  public Instant getScheduledAt() {
    return scheduledAt;
  }

  /**
   * Get when the action actually started.
   *
   * @return time action actually started
   */
  public Instant getStartedAt() {
    return startedAt;
  }

  /**
   * Action that took place.
   *
   * @return action started
   */
  public ScheduleActionExecution getAction() {
    return action;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleActionResult that = (ScheduleActionResult) o;
    return Objects.equals(scheduledAt, that.scheduledAt)
        && Objects.equals(startedAt, that.startedAt)
        && Objects.equals(action, that.action);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scheduledAt, startedAt, action);
  }

  @Override
  public String toString() {
    return "ScheduleActionResult{"
        + "scheduledAt="
        + scheduledAt
        + ", startedAt="
        + startedAt
        + ", action="
        + action
        + '}';
  }
}
