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

package io.temporal.internal.activity;

import com.google.protobuf.util.Timestamps;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

final class ActivityInfoImpl implements ActivityInfoInternal {
  private final PollActivityTaskQueueResponse response;
  private final String activityNamespace;
  private final boolean local;
  private final Functions.Proc completionHandle;

  ActivityInfoImpl(
      PollActivityTaskQueueResponse response,
      String activityNamespace,
      boolean local,
      Functions.Proc completionHandle) {
    this.response = Objects.requireNonNull(response);
    this.activityNamespace = Objects.requireNonNull(activityNamespace);
    this.local = local;
    this.completionHandle = completionHandle;
  }

  @Override
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
    return Timestamps.toMillis(response.getScheduledTime());
  }

  @Override
  public long getCurrentAttemptScheduledTimestamp() {
    return Timestamps.toMillis(response.getCurrentAttemptScheduledTime());
  }

  @Override
  public Duration getScheduleToCloseTimeout() {
    return ProtobufTimeUtils.toJavaDuration(response.getScheduleToCloseTimeout());
  }

  @Override
  public Duration getStartToCloseTimeout() {
    return ProtobufTimeUtils.toJavaDuration(response.getStartToCloseTimeout());
  }

  @Override
  public Duration getHeartbeatTimeout() {
    return ProtobufTimeUtils.toJavaDuration(response.getHeartbeatTimeout());
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

  @Override
  public boolean isLocal() {
    return local;
  }

  @Override
  public Functions.Proc getCompletionHandle() {
    return completionHandle;
  }

  @Override
  public Optional<Payloads> getInput() {
    if (response.hasInput()) {
      return Optional.of(response.getInput());
    }
    return Optional.empty();
  }

  @Override
  public Optional<Header> getHeader() {
    if (response.hasHeader()) {
      return Optional.of(response.getHeader());
    }
    return Optional.empty();
  }
}
