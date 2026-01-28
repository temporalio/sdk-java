package io.temporal.client.schedules;

/**
 * Base class for an action a schedule can take. See ScheduleActionStartWorkflow for the most
 * commonly used implementation.
 *
 * @see ScheduleActionStartWorkflow
 */
public abstract class ScheduleAction {
  ScheduleAction() {}
}
