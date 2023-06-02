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

/** Information about a listed schedule. */
public final class ScheduleListInfo {

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

  private final List<ScheduleActionResult> recentActions;
  private final List<Instant> nextActionTimes;

  public ScheduleListInfo(List<ScheduleActionResult> recentActions, List<Instant> nextActionTimes) {
    this.recentActions = recentActions;
    this.nextActionTimes = nextActionTimes;
  }
}
