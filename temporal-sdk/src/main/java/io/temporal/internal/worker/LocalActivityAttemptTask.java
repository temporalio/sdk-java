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

package io.temporal.internal.worker;

import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.workflow.Functions;
import java.util.concurrent.ScheduledFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class LocalActivityAttemptTask {
  private final @Nonnull LocalActivityExecutionContext executionContext;
  private final @Nonnull PollActivityTaskQueueResponse.Builder attemptTask;
  private final @Nullable Functions.Proc takenFromQueueCallback;
  private final @Nullable ScheduledFuture<?> scheduleToStartFuture;

  public LocalActivityAttemptTask(
      @Nonnull LocalActivityExecutionContext executionContext,
      @Nonnull PollActivityTaskQueueResponse.Builder attemptTask,
      @Nullable Functions.Proc takenFromQueueCallback,
      @Nullable ScheduledFuture<?> scheduleToStartFuture) {
    this.executionContext = executionContext;
    this.attemptTask = attemptTask;
    this.takenFromQueueCallback = takenFromQueueCallback;
    this.scheduleToStartFuture = scheduleToStartFuture;
  }

  @Nonnull
  public LocalActivityExecutionContext getExecutionContext() {
    return executionContext;
  }

  public String getActivityId() {
    return executionContext.getActivityId();
  }

  @Nonnull
  public PollActivityTaskQueueResponse.Builder getAttemptTask() {
    return attemptTask;
  }

  public void markAsTakenFromQueue() {
    executionContext.newAttempt();
    if (takenFromQueueCallback != null) {
      takenFromQueueCallback.apply();
    }
  }

  @Nullable
  public ScheduledFuture<?> getScheduleToStartFuture() {
    return scheduleToStartFuture;
  }
}
