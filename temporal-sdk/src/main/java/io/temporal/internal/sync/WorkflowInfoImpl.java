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

package io.temporal.internal.sync;

import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.RetryOptions;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.workflow.WorkflowInfo;
import java.time.Duration;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class WorkflowInfoImpl implements WorkflowInfo {

  private final ReplayWorkflowContext context;

  WorkflowInfoImpl(ReplayWorkflowContext context) {
    this.context = context;
  }

  @Override
  public String getNamespace() {
    return context.getNamespace();
  }

  @Override
  public String getWorkflowId() {
    return context.getWorkflowId();
  }

  @Override
  public String getWorkflowType() {
    return context.getWorkflowType().getName();
  }

  @Nonnull
  @Override
  public String getRunId() {
    return context.getRunId();
  }

  @Nonnull
  @Override
  public String getFirstExecutionRunId() {
    return context.getFirstExecutionRunId();
  }

  @Override
  public Optional<String> getContinuedExecutionRunId() {
    return context.getContinuedExecutionRunId();
  }

  @Nonnull
  @Override
  public String getOriginalExecutionRunId() {
    return context.getOriginalExecutionRunId();
  }

  @Override
  public String getTaskQueue() {
    return context.getTaskQueue();
  }

  @Nullable
  @Override
  public RetryOptions getRetryOptions() {
    return context.getRetryOptions();
  }

  @Nullable
  @Override
  public RetryOptions getRetryOptions() {
    return context.getRetryOptions();
  }

  @Override
  public Duration getWorkflowRunTimeout() {
    return context.getWorkflowRunTimeout();
  }

  @Override
  public Duration getWorkflowExecutionTimeout() {
    return context.getWorkflowExecutionTimeout();
  }

  @Override
  public long getRunStartedTimestampMillis() {
    return context.getRunStartedTimestampMillis();
  }

  @Override
  @SuppressWarnings("deprecation")
  @Nullable
  public SearchAttributes getSearchAttributes() {
    return context.getSearchAttributes();
  }

  @Override
  public Optional<String> getParentWorkflowId() {
    WorkflowExecution parentWorkflowExecution = context.getParentWorkflowExecution();
    return parentWorkflowExecution == null
        ? Optional.empty()
        : Optional.of(parentWorkflowExecution.getWorkflowId());
  }

  @Override
  public Optional<String> getParentRunId() {
    WorkflowExecution parentWorkflowExecution = context.getParentWorkflowExecution();
    return parentWorkflowExecution == null
        ? Optional.empty()
        : Optional.of(parentWorkflowExecution.getRunId());
  }

  @Override
  public int getAttempt() {
    return context.getAttempt();
  }

  @Override
  public String getCronSchedule() {
    return context.getCronSchedule();
  }

  @Override
  public long getHistoryLength() {
    return context.getLastWorkflowTaskStartedEventId();
  }

  @Override
  public long getHistorySize() {
    return context.getHistorySize();
  }

  @Override
  public boolean isContinueAsNewSuggested() {
    return context.isContinueAsNewSuggested();
  }

  @Override
  public Optional<String> getCurrentBuildId() {
    return context.getCurrentBuildId();
  }

  @Override
  public String toString() {
    return "WorkflowInfo{"
        + "namespace="
        + getNamespace()
        + ", workflowId="
        + getWorkflowId()
        + ", runId="
        + getRunId()
        + ", workflowType="
        + getWorkflowType()
        + ", continuedExecutionRunId="
        + getContinuedExecutionRunId()
        + ", taskQueue='"
        + getTaskQueue()
        + '\''
        + ", workflowRunTimeout="
        + getWorkflowRunTimeout()
        + ", workflowExecutionTimeout="
        + getWorkflowExecutionTimeout()
        + ", runStartedTimestampMillis="
        + getRunStartedTimestampMillis()
        + ", searchAttributes="
        + getSearchAttributes()
        + ", parentWorkflowId="
        + getParentWorkflowId()
        + ", parentRunId="
        + getParentRunId()
        + ", attempt="
        + getAttempt()
        + ", cronSchedule="
        + getCronSchedule()
        + ", historyLength="
        + getHistoryLength()
        + '}';
  }
}
