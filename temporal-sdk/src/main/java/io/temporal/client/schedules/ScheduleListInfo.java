package io.temporal.client.schedules;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Information about a listed schedule. */
public final class ScheduleListInfo {
  private final List<ScheduleActionResult> recentActions;
  private final List<Instant> nextActionTimes;

  public ScheduleListInfo(List<ScheduleActionResult> recentActions, List<Instant> nextActionTimes) {
    this.recentActions = recentActions;
    this.nextActionTimes = nextActionTimes;
  }

  /**
   * Most recent actions, oldest first. This may be a smaller count than ScheduleInfo.RecentActions
   *
   * @return The most recent action
   */
  public List<ScheduleActionResult> getRecentActions() {
    return recentActions;
  }

  /** Next scheduled action times. This may be a smaller count than ScheduleInfo.NextActions. */
  public List<Instant> getNextActionTimes() {
    return nextActionTimes;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleListInfo that = (ScheduleListInfo) o;
    return Objects.equals(recentActions, that.recentActions)
        && Objects.equals(nextActionTimes, that.nextActionTimes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(recentActions, nextActionTimes);
  }

  @Override
  public String toString() {
    return "ScheduleListInfo{"
        + "recentActions="
        + recentActions
        + ", nextActionTimes="
        + nextActionTimes
        + '}';
  }
}
