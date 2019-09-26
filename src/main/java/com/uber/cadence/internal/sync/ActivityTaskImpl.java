/*
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

package com.uber.cadence.internal.sync;

import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.activity.ActivityTask;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class ActivityTaskImpl implements ActivityTask {
  private final PollForActivityTaskResponse response;

  ActivityTaskImpl(PollForActivityTaskResponse response) {
    this.response = response;
  }

  @Override
  public byte[] getTaskToken() {
    return response.getTaskToken();
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
    // Cadence timestamp is in microseconds.
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
  public byte[] getHeartbeatDetails() {
    return response.getHeartbeatDetails();
  }

  @Override
  public WorkflowType getWorkflowType() {
    return response.getWorkflowType();
  }

  @Override
  public String getWorkflowDomain() {
    return response.getWorkflowDomain();
  }

  @Override
  public int getAttempt() {
    return response.getAttempt();
  }

  public byte[] getInput() {
    return response.getInput();
  }
}
