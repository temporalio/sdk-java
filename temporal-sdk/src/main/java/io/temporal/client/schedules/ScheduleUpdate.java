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

import io.temporal.common.SearchAttributes;

/** An update returned from a schedule updater. */
public final class ScheduleUpdate {
  private final Schedule schedule;
  private final SearchAttributes typedSearchAttributes;

  /**
   * Create a new ScheduleUpdate.
   *
   * @param schedule schedule to replace the existing schedule with
   */
  public ScheduleUpdate(Schedule schedule) {
    this.schedule = schedule;
    this.typedSearchAttributes = null;
  }

  /**
   * Create a new ScheduleUpdate.
   *
   * @param schedule schedule to replace the existing schedule with
   * @param typedSearchAttributes search attributes to replace the existing search attributes with.
   *     Returning null will not update the search attributes.
   */
  public ScheduleUpdate(Schedule schedule, SearchAttributes typedSearchAttributes) {
    this.schedule = schedule;
    this.typedSearchAttributes = typedSearchAttributes;
  }

  /**
   * Get the Schedule to update.
   *
   * @return schedule to update
   */
  public Schedule getSchedule() {
    return schedule;
  }

  /**
   * Get the search attributes to update.
   *
   * @return search attributes to update
   */
  public SearchAttributes getTypedSearchAttributes() {
    return typedSearchAttributes;
  }
}
