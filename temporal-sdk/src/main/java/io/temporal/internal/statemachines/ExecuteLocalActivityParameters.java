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
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ExecuteLocalActivityParameters {
  public static final long NOT_SCHEDULED = -1;

  // This builder doesn't have all the fields published yet (a specific attempt for example)
  // It contains only the fields known at the moment of scheduling from the workflow.
  // This template gets adjusted for each attempt.
  private final @Nonnull PollActivityTaskQueueResponse.Builder activityTaskBuilder;
  private final @Nonnull PollActivityTaskQueueResponse initialActivityTask;

  private final Duration localRetryThreshold;
  private final boolean doNotIncludeArgumentsIntoMarker;

  private @Nullable Duration scheduleToStartTimeout;

  /**
   * Timestamp of the moment when the first attempt of this local activity was scheduled. Comes into
   * play when localRetryThreshold is reached. If {@link #NOT_SCHEDULED} then the first attempt was
   * not scheduled yet, and we are going to do it now locally. This timestamp is registered by the
   * worker performing the first attempt, so this mechanic needs reasonably synchronized worker
   * clocks.
   */
  private long originalScheduledTimestamp = NOT_SCHEDULED;

  public ExecuteLocalActivityParameters(
      @Nonnull PollActivityTaskQueueResponse.Builder activityTaskBuilder,
      @Nullable Duration scheduleToStartTimeout,
      boolean doNotIncludeArgumentsIntoMarker,
      Duration localRetryThreshold) {
    this.activityTaskBuilder = Objects.requireNonNull(activityTaskBuilder, "activityTaskBuilder");
    this.initialActivityTask = activityTaskBuilder.build();
    this.scheduleToStartTimeout = scheduleToStartTimeout;
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
    return initialActivityTask.getAttempt();
  }

  /*
   * TODO This setter is exposed and the field is made non-final to support the legacy calculation of this timeout.
   *  This legacy logic doesn't make much sense anymore in presence of workflow task heartbeat and should be replaced
   *  with an explicit schedule to start timeout coming from the local activity options.
   */
  public void setScheduleToStartTimeout(@Nullable Duration scheduleToStartTimeout) {
    this.scheduleToStartTimeout = scheduleToStartTimeout;
  }

  @Nullable
  public Duration getScheduleToStartTimeout() {
    return scheduleToStartTimeout;
  }

  @Nullable
  public Duration getScheduleToCloseTimeout() {
    if (activityTaskBuilder.hasScheduleToCloseTimeout()) {
      return ProtobufTimeUtils.toJavaDuration(activityTaskBuilder.getScheduleToCloseTimeout());
    } else {
      return null;
    }
  }

  public boolean isDoNotIncludeArgumentsIntoMarker() {
    return doNotIncludeArgumentsIntoMarker;
  }

  public Duration getLocalRetryThreshold() {
    return localRetryThreshold;
  }

  public void setOriginalScheduledTimestamp(long scheduledTimestamp) {
    this.originalScheduledTimestamp = scheduledTimestamp;
  }

  public long getOriginalScheduledTimestamp() {
    return originalScheduledTimestamp;
  }

  @Nonnull
  public PollActivityTaskQueueResponse getInitialActivityTask() {
    return initialActivityTask;
  }

  // Keep usage of this method limited as modifying the protobuf builder is not thread safe
  @Nonnull
  public PollActivityTaskQueueResponse.Builder getActivityTaskBuilder() {
    return activityTaskBuilder;
  }
}
