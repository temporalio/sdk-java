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

import io.temporal.activity.ActivityTask;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.execution.WorkflowExecution;
import io.temporal.proto.workflowservice.PollForActivityTaskResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class ActivityTaskImpl implements ActivityTask {
  private final PollForActivityTaskResponse response;

  ActivityTaskImpl(PollForActivityTaskResponse response) {
    this.response = response;
  }

  @Override
  public byte[] getTaskToken() {
    return response.getTaskToken().toByteArray();
  }

  @Override
  public WorkflowExecution getWorkflowExecution() {
    return response.getWorkflowExecution();
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
  public WorkflowType getWorkflowType() {
    return response.getWorkflowType();
  }

  @Override
  public String getWorkflowNamespace() {
    return response.getWorkflowNamespace();
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
