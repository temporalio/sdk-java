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

import io.temporal.common.v1.SearchAttributes;
import io.temporal.common.v1.WorkflowExecution;
import io.temporal.internal.replay.DecisionContext;
import io.temporal.workflow.WorkflowInfo;
import java.time.Duration;
import java.util.Optional;

final class WorkflowInfoImpl implements WorkflowInfo {

  private final DecisionContext context;

  WorkflowInfoImpl(DecisionContext context) {
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
  public String getRunId() {
    return context.getRunId();
  }

  @Override
  public String getWorkflowType() {
    return context.getWorkflowType().getName();
  }

  @Override
  public Optional<String> getContinuedExecutionRunId() {
    return context.getContinuedExecutionRunId();
  }

  @Override
  public String getTaskQueue() {
    return context.getTaskQueue();
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
}
