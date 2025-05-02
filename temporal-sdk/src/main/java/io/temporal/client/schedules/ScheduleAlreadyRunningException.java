package io.temporal.client.schedules;

import io.temporal.failure.TemporalException;

/** Exception thrown by client when attempting to create a schedule that was already created. */
public final class ScheduleAlreadyRunningException extends TemporalException {
  public ScheduleAlreadyRunningException(Throwable cause) {
    super("Schedule already running", cause);
  }
}
