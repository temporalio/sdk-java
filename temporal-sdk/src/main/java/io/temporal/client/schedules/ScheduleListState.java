package io.temporal.client.schedules;

import java.util.Objects;

/** State of a listed schedule. */
public class ScheduleListState {
  private final String note;
  private final boolean paused;

  public ScheduleListState(String note, boolean paused) {
    this.note = note;
    this.paused = paused;
  }

  /** Human-readable message for the schedule. */
  public String getNote() {
    return note;
  }

  /** Whether the schedule is paused */
  public boolean isPaused() {
    return paused;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleListState that = (ScheduleListState) o;
    return paused == that.paused && Objects.equals(note, that.note);
  }

  @Override
  public int hashCode() {
    return Objects.hash(note, paused);
  }

  @Override
  public String toString() {
    return "ScheduleListState{" + "note='" + note + '\'' + ", paused=" + paused + '}';
  }
}
