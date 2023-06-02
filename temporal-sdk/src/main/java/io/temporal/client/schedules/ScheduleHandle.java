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

import io.temporal.workflow.Functions;
import java.util.List;
import javax.annotation.Nonnull;

/** Handle for interacting with a schedule. */
public interface ScheduleHandle {

  /**
   * Get this schedules ID.
   *
   * @return the schedules ID
   */
  String getId();

  /**
   * Backfill this schedule by going through the specified time periods as if they passed right now.
   *
   * @param backfills backfill requests to run
   */
  void backfill(List<ScheduleBackfill> backfills);

  /** Delete this schedule. */
  void delete();

  /**
   * Fetch this schedule's description.
   *
   * @return description of the schedule
   */
  ScheduleDescription describe();

  /**
   * Pause this schedule.
   *
   * @param note to set the schedule state.
   */
  void pause(@Nonnull String note);

  /** Pause this schedule. */
  void pause();

  /**
   * Trigger an action on this schedule to happen immediately.
   *
   * @param options Options for triggering.
   */
  void trigger(ScheduleTriggerOptions options);

  /** Trigger an action on this schedule to happen immediately. */
  void trigger();

  /**
   * Unpause this schedule.
   *
   * @param note to set the schedule state.
   */
  void unpause(@Nonnull String note);

  /** Unpause this schedule. */
  void unpause();

  /**
   * Update this schedule. This is done via a callback which can be called multiple times in case of
   * conflict.
   *
   * @param updater Callback to invoke with the current update input. The result can be null to
   *     signify no update to perform, or a schedule update instance with a schedule to perform an
   *     update.
   */
  void update(Functions.Func1<ScheduleUpdateInput, ScheduleUpdate> updater);
}
