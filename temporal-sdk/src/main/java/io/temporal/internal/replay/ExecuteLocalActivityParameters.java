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

package io.temporal.internal.replay;

import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import java.time.Duration;

public class ExecuteLocalActivityParameters {
  private final PollActivityTaskQueueResponse.Builder activityTask;
  private final Duration localRetryThreshold;
  private boolean doNotIncludeArgumentsIntoMarker;

  public ExecuteLocalActivityParameters(
      PollActivityTaskQueueResponse.Builder activityTask,
      Duration localRetryThreshold,
      boolean doNotIncludeArgumentsIntoMarker) {
    this.activityTask = activityTask;
    this.localRetryThreshold = localRetryThreshold;
    this.doNotIncludeArgumentsIntoMarker = doNotIncludeArgumentsIntoMarker;
  }

  public PollActivityTaskQueueResponse.Builder getActivityTask() {
    return activityTask;
  }

  public Duration getLocalRetryThreshold() {
    return localRetryThreshold;
  }

  public boolean isDoNotIncludeArgumentsIntoMarker() {
    return doNotIncludeArgumentsIntoMarker;
  }

  @Override
  public String toString() {
    return "ExecuteLocalActivityParameters{"
        + "activityTask="
        + activityTask
        + ", localRetryThreshold="
        + localRetryThreshold
        + '}';
  }
}
