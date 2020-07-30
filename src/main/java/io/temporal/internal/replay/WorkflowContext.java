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

package io.temporal.internal.replay;

import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.common.context.ContextPropagator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class WorkflowContext {

  private final PollWorkflowTaskQueueResponseOrBuilder workflowTask;
  private final long runStartedTimestampMillis;
  private boolean cancelRequested;
  private ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewOnCompletion;
  private WorkflowExecutionStartedEventAttributes startedAttributes;
  private final String namespace;
  // RunId can change when reset happens. This remembers the actual runId that is used
  // as in this particular part of the history.
  private String currentRunId;
  private SearchAttributes.Builder searchAttributes;
  private List<ContextPropagator> contextPropagators;

  WorkflowContext(
      String namespace,
      PollWorkflowTaskQueueResponseOrBuilder workflowTask,
      WorkflowExecutionStartedEventAttributes startedAttributes,
      long runStartedTimestampMillis,
      List<ContextPropagator> contextPropagators) {
    this.namespace = namespace;
    this.workflowTask = workflowTask;
    this.startedAttributes = startedAttributes;
    this.currentRunId = startedAttributes.getOriginalExecutionRunId();
    if (startedAttributes.hasSearchAttributes()) {
      this.searchAttributes = startedAttributes.getSearchAttributes().toBuilder();
    }
    this.runStartedTimestampMillis = runStartedTimestampMillis;
    this.contextPropagators = contextPropagators;
  }

  WorkflowExecution getWorkflowExecution() {
    return workflowTask.getWorkflowExecution();
  }

  WorkflowType getWorkflowType() {
    return workflowTask.getWorkflowType();
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

  int getWorkflowRunTimeoutSeconds() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return (int) Durations.toSeconds(attributes.getWorkflowRunTimeout());
  }

  int getWorkflowExecutionTimeoutSeconds() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return (int) Durations.toSeconds(attributes.getWorkflowExecutionTimeout());
  }

  long getWorkflowExecutionExpirationTimestampMillis() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return Timestamps.toMillis(attributes.getWorkflowExecutionExpirationTime());
  }

  long getRunStartedTimestampMillis() {
    return runStartedTimestampMillis;
  }

  int getWorkflowTaskTimeoutSeconds() {
    return (int) Durations.toSeconds(startedAttributes.getWorkflowTaskTimeout());
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

  void setCurrentRunId(String currentRunId) {
    this.currentRunId = currentRunId;
  }

  String getCurrentRunId() {
    return currentRunId;
  }

  SearchAttributes getSearchAttributes() {
    return searchAttributes == null || searchAttributes.getIndexedFieldsCount() == 0
        ? null
        : searchAttributes.build();
  }

  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  /** Returns a map of propagated context objects, keyed by propagator name */
  Map<String, Object> getPropagatedContexts() {
    if (contextPropagators == null || contextPropagators.isEmpty()) {
      return new HashMap<>();
    }

    Header headers = startedAttributes.getHeader();
    if (headers == null) {
      return new HashMap<>();
    }

    Map<String, Payload> headerData = new HashMap<>();
    for (Map.Entry<String, Payload> pair : headers.getFieldsMap().entrySet()) {
      headerData.put(pair.getKey(), pair.getValue());
    }

    Map<String, Object> contextData = new HashMap<>();
    for (ContextPropagator propagator : contextPropagators) {
      contextData.put(propagator.getName(), propagator.deserializeContext(headerData));
    }

    return contextData;
  }

  void mergeSearchAttributes(SearchAttributes searchAttributes) {
    if (searchAttributes == null) {
      return;
    }
    if (this.searchAttributes == null) {
      this.searchAttributes = SearchAttributes.newBuilder();
    }
    for (Map.Entry<String, Payload> pair : searchAttributes.getIndexedFieldsMap().entrySet()) {
      this.searchAttributes.putIndexedFields(pair.getKey(), pair.getValue());
    }
    if (searchAttributes.getIndexedFieldsCount() == 0) {
      this.searchAttributes = null;
    }
  }
}
