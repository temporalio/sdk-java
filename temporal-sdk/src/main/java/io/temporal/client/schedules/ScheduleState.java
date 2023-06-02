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

import java.util.Objects;

/** State of a schedule. */
public final class ScheduleState {
  public static ScheduleState.Builder newBuilder() {
    return new ScheduleState.Builder();
  }

  public static ScheduleState.Builder newBuilder(ScheduleState options) {
    return new ScheduleState.Builder(options);
  }

  public static ScheduleState getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final ScheduleState DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ScheduleState.newBuilder().build();
  }

  /**
   * Gets the human-readable message for the schedule.
   *
   * @return the schedules note
   */
  public String getNote() {
    return note;
  }

  /**
   * Gets a value indicating whether this schedule is paused.
   *
   * @return if the schedule is paused
   */
  public boolean isPaused() {
    return paused;
  }

  /**
   * Gets a value indicating whether, if true, remaining actions will be decremented for each action
   * taken.
   *
   * @return if the schedule has limited actions
   */
  public boolean isLimitedAction() {
    return limitedAction;
  }

  /**
   * Gets the actions remaining on this schedule. Once this number hits 0, no further actions are
   * scheduled automatically.
   *
   * @return the number of remaining actions
   */
  public long getRemainingActions() {
    return remainingActions;
  }

  private final String note;
  private final boolean paused;
  private final boolean limitedAction;
  private final long remainingActions;

  private ScheduleState(String note, boolean paused, boolean limitedAction, long remainingActions) {
    this.note = note;
    this.paused = paused;
    this.limitedAction = limitedAction;
    this.remainingActions = remainingActions;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleState that = (ScheduleState) o;
    return paused == that.paused
        && limitedAction == that.limitedAction
        && remainingActions == that.remainingActions
        && Objects.equals(note, that.note);
  }

  @Override
  public int hashCode() {
    return Objects.hash(note, paused, limitedAction, remainingActions);
  }

  @Override
  public String toString() {
    return "ScheduleState{"
        + "note='"
        + note
        + '\''
        + ", paused="
        + paused
        + ", limitedAction="
        + limitedAction
        + ", remainingActions="
        + remainingActions
        + '}';
  }

  public static final class Builder {

    /** Set a human-readable message for the schedule. */
    public Builder setNote(String note) {
      this.note = note;
      return this;
    }

    /** Set whether this schedule is paused. */
    public Builder setPaused(boolean paused) {
      this.paused = paused;
      return this;
    }

    /** Set ,if true, whether remaining actions will be decremented for each action */
    public Builder setLimitedAction(boolean limitedAction) {
      this.limitedAction = limitedAction;
      return this;
    }

    /**
     * Set the actions remaining on this schedule. Once this number hits 0, no further actions are
     * scheduled automatically.
     */
    public Builder setRemainingActions(long remainingActions) {
      this.remainingActions = remainingActions;
      return this;
    }

    private String note;
    private boolean paused;
    private boolean limitedAction;
    private long remainingActions;

    private Builder() {}

    private Builder(ScheduleState options) {
      if (options == null) {
        return;
      }
      this.note = options.note;
      this.paused = options.paused;
      this.limitedAction = options.limitedAction;
      this.remainingActions = options.remainingActions;
    }

    public ScheduleState build() {
      return new ScheduleState(note, paused, limitedAction, remainingActions);
    }
  }
}
