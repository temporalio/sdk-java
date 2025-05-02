package io.temporal.client.schedules;

/** Parameter passed to a schedule updater. */
public final class ScheduleUpdateInput {
  private final ScheduleDescription description;

  public ScheduleUpdateInput(ScheduleDescription description) {
    this.description = description;
  }

  /**
   * Description fetched from the server before this update.
   *
   * @return description of schedule to be updated
   */
  public ScheduleDescription getDescription() {
    return this.description;
  }
}
