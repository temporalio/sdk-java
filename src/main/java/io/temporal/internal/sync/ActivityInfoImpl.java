/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.sync;

import io.temporal.activity.ActivityInfo;
import io.temporal.common.v1.Payloads;
import io.temporal.workflowservice.v1.PollForActivityTaskResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class ActivityInfoImpl implements ActivityInfo {
  private final PollForActivityTaskResponse response;
  private final String activityNamespace;

  ActivityInfoImpl(PollForActivityTaskResponse response, String activityNamespace) {
    this.response = Objects.requireNonNull(response);
    this.activityNamespace = Objects.requireNonNull(activityNamespace);
  }

  public byte[] getTaskToken() {
    return response.getTaskToken().toByteArray();
  }

  @Override
  public String getWorkflowId() {
    return response.getWorkflowExecution().getWorkflowId();
  }

  @Override
  public String getRunId() {
    return response.getWorkflowExecution().getRunId();
  }

  @Override
  public String getActivityId() {
    return response.getActivityId();
  }

  @Override
  public String getActivityType() {
    return response.getActivityType().getName();
  }

  @Override
  public long getScheduledTimestamp() {
    // Temporal timestamp is in microseconds.
    return TimeUnit.MICROSECONDS.toMillis(response.getScheduledTimestamp());
  }

  @Override
  public Duration getScheduleToCloseTimeout() {
    return Duration.ofSeconds(response.getScheduleToCloseTimeoutSeconds());
  }

  @Override
  public Duration getStartToCloseTimeout() {
    return Duration.ofSeconds(response.getStartToCloseTimeoutSeconds());
  }

  @Override
  public Duration getHeartbeatTimeout() {
    return Duration.ofSeconds(response.getHeartbeatTimeoutSeconds());
  }

  @Override
  public Optional<Payloads> getHeartbeatDetails() {
    if (response.hasHeartbeatDetails()) {
      return Optional.of(response.getHeartbeatDetails());
    } else {
      return Optional.empty();
    }
  }

  @Override
  public String getWorkflowType() {
    return response.getWorkflowType().getName();
  }

  @Override
  public String getWorkflowNamespace() {
    return response.getWorkflowNamespace();
  }

  @Override
  public String getActivityNamespace() {
    return activityNamespace;
  }

  @Override
  public int getAttempt() {
    return response.getAttempt();
  }

  public Optional<Payloads> getInput() {
    if (response.hasInput()) {
      return Optional.of(response.getInput());
    }
    return Optional.empty();
  }
}
