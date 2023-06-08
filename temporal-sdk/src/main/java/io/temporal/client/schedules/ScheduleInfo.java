/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.client.schedules;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/** Information about a schedule. */
public final class ScheduleInfo {
  private final long numActions;
  private final long numActionsMissedCatchupWindow;
  private final long numActionsSkippedOverlap;
  private final List<ScheduleActionExecution> runningActions;
  private final List<ScheduleActionResult> recentActions;
  private final List<Instant> nextActionTimes;
  private final Instant createdAt;
  private final Instant lastUpdatedAt;

  public ScheduleInfo(
      long numActions,
      long numActionsMissedCatchupWindow,
      long numActionsSkippedOverlap,
      List<ScheduleActionExecution> runningActions,
      List<ScheduleActionResult> recentActions,
      List<Instant> nextActionTimes,
      Instant createdAt,
      Instant lastUpdatedAt) {
    this.numActions = numActions;
    this.numActionsMissedCatchupWindow = numActionsMissedCatchupWindow;
    this.numActionsSkippedOverlap = numActionsSkippedOverlap;
    this.runningActions = runningActions;
    this.recentActions = recentActions;
    this.nextActionTimes = nextActionTimes;
    this.createdAt = createdAt;
    this.lastUpdatedAt = lastUpdatedAt;
  }

  /**
   * Get the number of actions taken by the schedule.
   *
   * @return number of actions taken
   */
  public long getNumActions() {
    return numActions;
  }

  /**
   * Get the number of actions skipped due to missing the catchup window.
   *
   * @return number of actions skipped due to catchup window
   */
  public long getNumActionsMissedCatchupWindow() {
    return numActionsMissedCatchupWindow;
  }

  /**
   * Get the number of actions skipped due to overlap.
   *
   * @return number of actions skipped due to overlap
   */
  public long getNumActionsSkippedOverlap() {
    return numActionsSkippedOverlap;
  }

  /**
   * Get a list of currently running actions.
   *
   * @return list of currently running actions
   */
  public List<ScheduleActionExecution> getRunningActions() {
    return runningActions;
  }

  /**
   * Get a list of the most recent actions, oldest first.
   *
   * @return list of the most recent actions
   */
  public List<ScheduleActionResult> getRecentActions() {
    return recentActions;
  }

  /**
   * Get a list of the next scheduled action times.
   *
   * @return list of the next recent times
   */
  public List<Instant> getNextActionTimes() {
    return nextActionTimes;
  }

  /**
   * Get the time the schedule was created at.
   *
   * @return time the schedule was created
   */
  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * Get the last time the schedule was updated.
   *
   * @return last time the schedule was updated
   */
  @Nullable
  public Instant getLastUpdatedAt() {
    return lastUpdatedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleInfo that = (ScheduleInfo) o;
    return numActions == that.numActions
        && numActionsMissedCatchupWindow == that.numActionsMissedCatchupWindow
        && numActionsSkippedOverlap == that.numActionsSkippedOverlap
        && Objects.equals(runningActions, that.runningActions)
        && Objects.equals(recentActions, that.recentActions)
        && Objects.equals(nextActionTimes, that.nextActionTimes)
        && Objects.equals(createdAt, that.createdAt)
        && Objects.equals(lastUpdatedAt, that.lastUpdatedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        numActions,
        numActionsMissedCatchupWindow,
        numActionsSkippedOverlap,
        runningActions,
        recentActions,
        nextActionTimes,
        createdAt,
        lastUpdatedAt);
  }

  @Override
  public String toString() {
    return "ScheduleInfo{"
        + "numActions="
        + numActions
        + ", numActionsMissedCatchupWindow="
        + numActionsMissedCatchupWindow
        + ", numActionsSkippedOverlap="
        + numActionsSkippedOverlap
        + ", runningActions="
        + runningActions
        + ", recentActions="
        + recentActions
        + ", nextActionTimes="
        + nextActionTimes
        + ", createdAt="
        + createdAt
        + ", lastUpdatedAt="
        + lastUpdatedAt
        + '}';
  }
}
