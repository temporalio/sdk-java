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

import com.google.common.base.Preconditions;
import com.google.protobuf.util.Timestamps;
import io.temporal.api.common.v1.*;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The most basic context with an information about the Workflow and some mutable state that
 * collects during its execution. This context is not aware about anything else like state machines.
 */
final class BasicWorkflowContext {
  private final long runStartedTimestampMillis;
  private final WorkflowExecutionStartedEventAttributes startedAttributes;
  private final String namespace;
  @Nonnull private final WorkflowExecution workflowExecution;

  @Nullable private final Payloads lastCompletionResult;

  @Nullable private final Failure previousRunFailure;

  BasicWorkflowContext(
      String namespace,
      @Nonnull WorkflowExecution workflowExecution,
      WorkflowExecutionStartedEventAttributes startedAttributes,
      long runStartedTimestampMillis) {
    this.namespace = namespace;
    this.workflowExecution = Preconditions.checkNotNull(workflowExecution);
    this.startedAttributes = startedAttributes;
    this.runStartedTimestampMillis = runStartedTimestampMillis;
    this.lastCompletionResult =
        startedAttributes.hasLastCompletionResult()
            ? startedAttributes.getLastCompletionResult()
            : null;
    this.previousRunFailure =
        startedAttributes.hasContinuedFailure() ? startedAttributes.getContinuedFailure() : null;
  }

  @Nonnull
  WorkflowExecution getWorkflowExecution() {
    return workflowExecution;
  }

  WorkflowType getWorkflowType() {
    return startedAttributes.getWorkflowType();
  }

  @Nonnull
  String getFirstExecutionRunId() {
    return startedAttributes.getFirstExecutionRunId();
  }

  Optional<String> getContinuedExecutionRunId() {
    String runId = startedAttributes.getContinuedExecutionRunId();
    return runId.isEmpty() ? Optional.empty() : Optional.of(runId);
  }

  @Nonnull
  String getOriginalExecutionRunId() {
    return startedAttributes.getOriginalExecutionRunId();
  }

  WorkflowExecution getParentWorkflowExecution() {
    return startedAttributes.hasParentWorkflowExecution()
        ? startedAttributes.getParentWorkflowExecution()
        : null;
  }

  Duration getWorkflowRunTimeout() {
    return ProtobufTimeUtils.toJavaDuration(startedAttributes.getWorkflowRunTimeout());
  }

  Duration getWorkflowExecutionTimeout() {
    return ProtobufTimeUtils.toJavaDuration(startedAttributes.getWorkflowExecutionTimeout());
  }

  long getWorkflowExecutionExpirationTimestampMillis() {
    return Timestamps.toMillis(startedAttributes.getWorkflowExecutionExpirationTime());
  }

  long getRunStartedTimestampMillis() {
    return runStartedTimestampMillis;
  }

  Duration getWorkflowTaskTimeout() {
    return ProtobufTimeUtils.toJavaDuration(startedAttributes.getWorkflowTaskTimeout());
  }

  String getTaskQueue() {
    return startedAttributes.getTaskQueue().getName();
  }

  String getNamespace() {
    return namespace;
  }

  public Map<String, Payload> getHeader() {
    return startedAttributes.getHeader().getFieldsMap();
  }

  public Payload getMemo(String key) {
    return startedAttributes.getMemo().getFieldsMap().get(key);
  }

  int getAttempt() {
    return startedAttributes.getAttempt();
  }

  public String getCronSchedule() {
    return startedAttributes.getCronSchedule();
  }

  @Nullable
  public Payloads getLastCompletionResult() {
    return lastCompletionResult;
  }

  @Nullable
  public Failure getPreviousRunFailure() {
    return previousRunFailure;
  }
}
