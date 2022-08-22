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
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
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

  // Mutable, accumulated during execution:
  private SearchAttributes.Builder searchAttributes;
  private boolean cancelRequested;
  private ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewOnCompletion;

  BasicWorkflowContext(
      String namespace,
      @Nonnull WorkflowExecution workflowExecution,
      WorkflowExecutionStartedEventAttributes startedAttributes,
      long runStartedTimestampMillis) {
    this.namespace = namespace;
    this.workflowExecution = Preconditions.checkNotNull(workflowExecution);
    this.startedAttributes = startedAttributes;
    if (startedAttributes.hasSearchAttributes()) {
      this.searchAttributes = startedAttributes.getSearchAttributes().toBuilder();
    }
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

  boolean isCancelRequested() {
    return cancelRequested;
  }

  void setCancelRequested(boolean flag) {
    cancelRequested = flag;
  }

  ContinueAsNewWorkflowExecutionCommandAttributes getContinueAsNewOnCompletion() {
    return continueAsNewOnCompletion;
  }

  void setContinueAsNewOnCompletion(ContinueAsNewWorkflowExecutionCommandAttributes parameters) {
    this.continueAsNewOnCompletion = parameters;
  }

  Optional<String> getContinuedExecutionRunId() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    String runId = attributes.getContinuedExecutionRunId();
    return runId.isEmpty() ? Optional.empty() : Optional.of(runId);
  }

  WorkflowExecution getParentWorkflowExecution() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return attributes.hasParentWorkflowExecution() ? attributes.getParentWorkflowExecution() : null;
  }

  Duration getWorkflowRunTimeout() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return ProtobufTimeUtils.toJavaDuration(attributes.getWorkflowRunTimeout());
  }

  Duration getWorkflowExecutionTimeout() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return ProtobufTimeUtils.toJavaDuration(attributes.getWorkflowExecutionTimeout());
  }

  long getWorkflowExecutionExpirationTimestampMillis() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return Timestamps.toMillis(attributes.getWorkflowExecutionExpirationTime());
  }

  long getRunStartedTimestampMillis() {
    return runStartedTimestampMillis;
  }

  Duration getWorkflowTaskTimeout() {
    return ProtobufTimeUtils.toJavaDuration(startedAttributes.getWorkflowTaskTimeout());
  }

  String getTaskQueue() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return attributes.getTaskQueue().getName();
  }

  String getNamespace() {
    return namespace;
  }

  private WorkflowExecutionStartedEventAttributes getWorkflowStartedEventAttributes() {
    return startedAttributes;
  }

  public Map<String, Payload> getHeader() {
    return startedAttributes.getHeader().getFieldsMap();
  }

  public Payload getMemo(String key) {
    return startedAttributes.getMemo().getFieldsMap().get(key);
  }

  @Nullable
  SearchAttributes getSearchAttributes() {
    return searchAttributes == null || searchAttributes.getIndexedFieldsCount() == 0
        ? null
        : searchAttributes.build();
  }

  int getAttempt() {
    return startedAttributes.getAttempt();
  }

  void mergeSearchAttributes(SearchAttributes searchAttributes) {
    if (searchAttributes == null || searchAttributes.getIndexedFieldsCount() == 0) {
      return;
    }
    if (this.searchAttributes == null) {
      this.searchAttributes = SearchAttributes.newBuilder();
    }
    this.searchAttributes.putAllIndexedFields(searchAttributes.getIndexedFieldsMap());
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
