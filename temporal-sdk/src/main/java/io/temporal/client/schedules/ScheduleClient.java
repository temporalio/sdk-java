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

import java.util.stream.Stream;

/**
 * Client to the Temporal service used to create, list and get handles to Schedules.
 *
 * @see ScheduleHandle
 */
public interface ScheduleClient {

  /**
   * Create a schedule and return its handle.
   *
   * @param scheduleID Unique ID for the schedule.
   * @param schedule Schedule to create.
   * @param options Options for creating the schedule.
   * @throws ScheduleAlreadyRunningException if the schedule is already runnning.
   * @return A handle that can be used to perform operations on a schedule.
   */
  ScheduleHandle createSchedule(String scheduleID, Schedule schedule, ScheduleOptions options);

  /**
   * Gets the schedule handle for the given ID.
   *
   * @param scheduleID Schedule ID to get the handle for.
   * @return A handle that can be used to perform operations on a schedule.
   */
  ScheduleHandle getHandle(String scheduleID);

  /**
   * List schedules.
   *
   * @param options for the list call.
   * @return sequential stream that performs remote pagination under the hood
   */
  Stream<ScheduleListDescription> listSchedules(ScheduleListOptions options);
}
