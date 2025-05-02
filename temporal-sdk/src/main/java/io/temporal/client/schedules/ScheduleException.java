package io.temporal.client.schedules;

import io.temporal.failure.TemporalException;

/** Exception thrown by client when attempting to create a schedule. */
public final class ScheduleException extends TemporalException {
  public ScheduleException(Throwable cause) {
    super("Schedule exception", cause);
  }
}
