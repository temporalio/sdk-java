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

import com.google.common.base.Preconditions;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptor;
import io.temporal.workflow.Functions;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

public class ScheduleHandleImpl implements ScheduleHandle {
  private final String Id;
  private final ScheduleClientCallsInterceptor interceptor;

  public ScheduleHandleImpl(ScheduleClientCallsInterceptor interceptor, String Id) {
    this.interceptor = interceptor;
    this.Id = Id;
  }

  @Override
  public String getId() {
    return this.Id;
  }

  @Override
  public void backfill(List<ScheduleBackfill> backfills) {
    Preconditions.checkState(backfills.size() > 0, "At least one backfill required");
    interceptor.backfillSchedule(
        new ScheduleClientCallsInterceptor.BackfillScheduleInput(Id, backfills));
  }

  @Override
  public void delete() {
    interceptor.deleteSchedule(new ScheduleClientCallsInterceptor.DeleteScheduleInput(Id));
  }

  @Override
  public ScheduleDescription describe() {
    return interceptor
        .describeSchedule(new ScheduleClientCallsInterceptor.DescribeScheduleInput(Id))
        .getDescription();
  }

  @Override
  public void pause(@Nonnull String note) {
    Objects.requireNonNull(note);
    interceptor.pauseSchedule(
        new ScheduleClientCallsInterceptor.PauseScheduleInput(
            Id, note.isEmpty() ? "Paused via Java SDK" : note));
  }

  @Override
  public void pause() {
    pause("");
  }

  @Override
  public void trigger(ScheduleTriggerOptions options) {
    interceptor.triggerSchedule(
        new ScheduleClientCallsInterceptor.TriggerScheduleInput(Id, options.getOverlapPolicy()));
  }

  @Override
  public void trigger() {
    interceptor.triggerSchedule(
        new ScheduleClientCallsInterceptor.TriggerScheduleInput(
            Id, ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_UNSPECIFIED));
  }

  @Override
  public void unpause(@Nonnull String note) {
    Objects.requireNonNull(note);
    interceptor.unpauseSchedule(
        new ScheduleClientCallsInterceptor.UnpauseScheduleInput(
            Id, note.isEmpty() ? "Unpaused via Java SDK" : note));
  }

  @Override
  public void unpause() {
    unpause("");
  }

  @Override
  public void update(Functions.Func1<ScheduleUpdateInput, ScheduleUpdate> updater) {
    interceptor.updateSchedule(
        new ScheduleClientCallsInterceptor.UpdateScheduleInput(this.describe(), updater));
  }
}
