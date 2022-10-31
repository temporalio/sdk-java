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
import io.temporal.internal.statemachines.ExecuteLocalActivityParameters;
import javax.annotation.Nonnull;

// TODO This class is an absolutely trivial wrapper and may go away with reworking of local activity
//  scheduling from the generic poller classes.
class LocalActivityAttemptTask {
  private final @Nonnull LocalActivityExecutionContext executionContext;
  private final @Nonnull PollActivityTaskQueueResponse attemptTask;

  public LocalActivityAttemptTask(
      @Nonnull LocalActivityExecutionContext executionContext,
      @Nonnull PollActivityTaskQueueResponse attemptTask) {
    this.executionContext = executionContext;
    this.attemptTask = attemptTask;
  }

  @Nonnull
  public LocalActivityExecutionContext getExecutionContext() {
    return executionContext;
  }

  public String getActivityId() {
    return executionContext.getActivityId();
  }

  @Nonnull
  public ExecuteLocalActivityParameters getExecutionParams() {
    return executionContext.getExecutionParams();
  }

  @Nonnull
  public PollActivityTaskQueueResponse getAttemptTask() {
    return attemptTask;
  }
}
