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

package io.temporal.internal.statemachines;

import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ExecuteLocalActivityParameters {

  // This builder doesn't have all the fields published yet (a specific attempt for example)
  // It contains only the fields known at the moment of scheduling from the workflow.
  // This template gets adjusted for each attempt.
  private final @Nonnull PollActivityTaskQueueResponse.Builder activityTaskBuilder;

  /**
   * This timestamp is a Workflow Time ({@link Workflow#currentTimeMillis()}) at the moment of
   * scheduling of the first attempt. Comes into play when localRetryThreshold is reached. This
   * mechanic requires reasonably synchronized worker clocks to work properly.
   */
  private final long originalScheduledTimestamp;

  private final @Nullable Failure previousLocalExecutionFailure;
  private final @Nonnull Duration localRetryThreshold;
  private final boolean doNotIncludeArgumentsIntoMarker;
  private final @Nullable Duration scheduleToStartTimeout;

  public ExecuteLocalActivityParameters(
      @Nonnull PollActivityTaskQueueResponse.Builder activityTaskBuilder,
      @Nullable Duration scheduleToStartTimeout,
      long originalScheduledTimestamp,
      @Nullable Failure previousLocalExecutionFailure,
      boolean doNotIncludeArgumentsIntoMarker,
      @Nonnull Duration localRetryThreshold) {
    this.activityTaskBuilder = Objects.requireNonNull(activityTaskBuilder, "activityTaskBuilder");
    this.scheduleToStartTimeout = scheduleToStartTimeout;
    this.originalScheduledTimestamp = originalScheduledTimestamp;
    this.previousLocalExecutionFailure = previousLocalExecutionFailure;
    this.doNotIncludeArgumentsIntoMarker = doNotIncludeArgumentsIntoMarker;
    this.localRetryThreshold = localRetryThreshold;
  }

  public String getActivityId() {
    return activityTaskBuilder.getActivityId();
  }

  public ActivityType getActivityType() {
    return activityTaskBuilder.getActivityType();
  }

  public Payloads getInput() {
    return activityTaskBuilder.getInput();
  }

  public int getInitialAttempt() {
    return activityTaskBuilder.getAttempt();
  }

  /**
   * @return cloned version of the original activity task builder supplied to these parameters to be
   *     used as an attempt base
   */
  @Nonnull
  public PollActivityTaskQueueResponse.Builder cloneActivityTaskBuilder() {
    return activityTaskBuilder.clone();
  }

  @Nullable
  public Duration getScheduleToCloseTimeout() {
    if (activityTaskBuilder.hasScheduleToCloseTimeout()) {
      return ProtobufTimeUtils.toJavaDuration(activityTaskBuilder.getScheduleToCloseTimeout());
    } else {
      return null;
    }
  }

  public long getOriginalScheduledTimestamp() {
    return originalScheduledTimestamp;
  }

  @Nullable
  public Failure getPreviousLocalExecutionFailure() {
    return previousLocalExecutionFailure;
  }

  public boolean isDoNotIncludeArgumentsIntoMarker() {
    return doNotIncludeArgumentsIntoMarker;
  }

  @Nonnull
  public Duration getLocalRetryThreshold() {
    return localRetryThreshold;
  }

  @Nullable
  public Duration getScheduleToStartTimeout() {
    return scheduleToStartTimeout;
  }
}
