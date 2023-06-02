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

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import java.time.Duration;
import java.util.Objects;

/** Policies of a schedule. */
public final class SchedulePolicy {
  public static SchedulePolicy.Builder newBuilder() {
    return new SchedulePolicy.Builder();
  }

  public static SchedulePolicy.Builder newBuilder(SchedulePolicy options) {
    return new SchedulePolicy.Builder(options);
  }

  public static SchedulePolicy getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final SchedulePolicy DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = SchedulePolicy.newBuilder().build();
  }

  /**
   * Gets the policy for what happens when an action is started while another is still running.
   *
   * @return the schedules overlap policy
   */
  public ScheduleOverlapPolicy getOverlap() {
    return overlap;
  }

  /**
   * Gets the amount of time in the past to execute missed actions after a Temporal server is
   * unavailable.
   *
   * @return the schedules catchup window
   */
  public Duration getCatchupWindow() {
    return catchupWindow;
  }

  /**
   * Gets a value indicating whether to pause the schedule if an action fails or times out.
   *
   * @return if the schedule should pause on failure
   */
  public boolean isPauseOnFailure() {
    return pauseOnFailure;
  }

  private final ScheduleOverlapPolicy overlap;
  private final Duration catchupWindow;
  private final boolean pauseOnFailure;

  private SchedulePolicy(
      ScheduleOverlapPolicy overlap, Duration catchupWindow, boolean pauseOnFailure) {
    this.overlap = overlap;
    this.catchupWindow = catchupWindow;
    this.pauseOnFailure = pauseOnFailure;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SchedulePolicy that = (SchedulePolicy) o;
    return pauseOnFailure == that.pauseOnFailure
        && overlap == that.overlap
        && Objects.equals(catchupWindow, that.catchupWindow);
  }

  @Override
  public int hashCode() {
    return Objects.hash(overlap, catchupWindow, pauseOnFailure);
  }

  @Override
  public String toString() {
    return "SchedulePolicy{"
        + "overlap="
        + overlap
        + ", catchupWindow="
        + catchupWindow
        + ", pauseOnFailure="
        + pauseOnFailure
        + '}';
  }

  public static class Builder {

    /** Set the policy for what happens when an action is started while another is still running. */
    public Builder setOverlap(ScheduleOverlapPolicy overlap) {
      this.overlap = overlap;
      return this;
    }

    /**
     * Set the amount of time in the past to execute missed actions after a Temporal server is
     * unavailable.
     */
    public Builder setCatchupWindow(Duration catchupWindow) {
      this.catchupWindow = catchupWindow;
      return this;
    }

    /** Set whether to pause the schedule if an action fails or times out. */
    public Builder setPauseOnFailure(boolean pauseOnFailure) {
      this.pauseOnFailure = pauseOnFailure;
      return this;
    }

    private ScheduleOverlapPolicy overlap;
    private Duration catchupWindow;
    private boolean pauseOnFailure;

    private Builder() {}

    private Builder(SchedulePolicy options) {
      if (options == null) {
        return;
      }
      this.overlap = options.overlap;
      this.catchupWindow = options.catchupWindow;
      this.pauseOnFailure = options.pauseOnFailure;
    }

    public SchedulePolicy build() {
      return new SchedulePolicy(
          overlap == null ? ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP : overlap,
          catchupWindow,
          pauseOnFailure);
    }
  }
}
