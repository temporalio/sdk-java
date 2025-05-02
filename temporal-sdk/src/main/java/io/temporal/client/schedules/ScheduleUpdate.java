package io.temporal.client.schedules;

import io.temporal.common.SearchAttributes;

/** An update returned from a schedule updater. */
public final class ScheduleUpdate {
  private final Schedule schedule;
  private final SearchAttributes typedSearchAttributes;

  /**
   * Create a new ScheduleUpdate.
   *
   * @param schedule schedule to replace the existing schedule with
   */
  public ScheduleUpdate(Schedule schedule) {
    this.schedule = schedule;
    this.typedSearchAttributes = null;
  }

  /**
   * Create a new ScheduleUpdate.
   *
   * @param schedule schedule to replace the existing schedule with
   * @param typedSearchAttributes search attributes to replace the existing search attributes with.
   *     Returning null will not update the search attributes.
   */
  public ScheduleUpdate(Schedule schedule, SearchAttributes typedSearchAttributes) {
    this.schedule = schedule;
    this.typedSearchAttributes = typedSearchAttributes;
  }

  /**
   * Get the Schedule to update.
   *
   * @return schedule to update
   */
  public Schedule getSchedule() {
    return schedule;
  }

  /**
   * Get the search attributes to update.
   *
   * @return search attributes to update
   */
  public SearchAttributes getTypedSearchAttributes() {
    return typedSearchAttributes;
  }
}
