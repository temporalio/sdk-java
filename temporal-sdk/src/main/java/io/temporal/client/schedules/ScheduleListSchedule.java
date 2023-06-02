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

/** Details for a listed schedule. */
public final class ScheduleListSchedule {

  /**
   * Get the action taken when scheduled.
   *
   * @return Action taken when scheduled
   */
  public ScheduleListAction getAction() {
    return action;
  }

  /**
   * Get when the action is taken.
   *
   * @return When the action is taken
   */
  public ScheduleSpec getSpec() {
    return spec;
  }

  /**
   * Get the state of the schedule.
   *
   * @return State of the schedule.
   */
  public ScheduleListState getState() {
    return state;
  }

  private final ScheduleListAction action;
  private final ScheduleSpec spec;
  private final ScheduleListState state;

  public ScheduleListSchedule(
      ScheduleListAction action, ScheduleSpec spec, ScheduleListState state) {
    this.action = action;
    this.spec = spec;
    this.state = state;
  }
}
