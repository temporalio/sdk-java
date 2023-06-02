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

package io.temporal.common.interceptors;

/** Convenience base class for {@link ScheduleClientCallsInterceptor} implementations. */
public class ScheduleClientCallsInterceptorBase implements ScheduleClientCallsInterceptor {

  private final ScheduleClientCallsInterceptor next;

  public ScheduleClientCallsInterceptorBase(ScheduleClientCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public void createSchedule(CreateScheduleInput input) {
    next.createSchedule(input);
  }

  @Override
  public ListScheduleOutput listSchedules(ListSchedulesInput input) {
    return next.listSchedules(input);
  }

  @Override
  public void backfillSchedule(BackfillScheduleInput input) {
    next.backfillSchedule(input);
  }

  @Override
  public void deleteSchedule(DeleteScheduleInput input) {
    next.deleteSchedule(input);
  }

  @Override
  public DescribeScheduleOutput describeSchedule(DescribeScheduleInput input) {
    return next.describeSchedule(input);
  }

  @Override
  public void pauseSchedule(PauseScheduleInput input) {
    next.pauseSchedule(input);
  }

  @Override
  public void triggerSchedule(TriggerScheduleInput input) {
    next.triggerSchedule(input);
  }

  @Override
  public void unpauseSchedule(UnpauseScheduleInput input) {
    next.unpauseSchedule(input);
  }

  @Override
  public void updateSchedule(UpdateScheduleInput input) {
    next.updateSchedule(input);
  }
}
