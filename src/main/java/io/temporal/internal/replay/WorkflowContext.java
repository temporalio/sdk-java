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

import io.temporal.common.context.ContextPropagator;
import io.temporal.common.v1.Header;
import io.temporal.common.v1.Payload;
import io.temporal.common.v1.SearchAttributes;
import io.temporal.common.v1.WorkflowExecution;
import io.temporal.common.v1.WorkflowType;
import io.temporal.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.workflowservice.v1.PollForDecisionTaskResponseOrBuilder;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class WorkflowContext {

  private final PollForDecisionTaskResponseOrBuilder decisionTask;
  private final long runStartedTimestampMillis;
  private boolean cancelRequested;
  private ContinueAsNewWorkflowExecutionParameters continueAsNewOnCompletion;
  private WorkflowExecutionStartedEventAttributes startedAttributes;
  private final String namespace;
  // RunId can change when reset happens. This remembers the actual runId that is used
  // as in this particular part of the history.
  private String currentRunId;
  private SearchAttributes.Builder searchAttributes;
  private List<ContextPropagator> contextPropagators;

  WorkflowContext(
      String namespace,
      PollForDecisionTaskResponseOrBuilder decisionTask,
      WorkflowExecutionStartedEventAttributes startedAttributes,
      long runStartedTimestampMillis,
      List<ContextPropagator> contextPropagators) {
    this.namespace = namespace;
    this.decisionTask = decisionTask;
    this.startedAttributes = startedAttributes;
    this.currentRunId = startedAttributes.getOriginalExecutionRunId();
    if (startedAttributes.hasSearchAttributes()) {
      this.searchAttributes = startedAttributes.getSearchAttributes().toBuilder();
    }
    this.runStartedTimestampMillis = runStartedTimestampMillis;
    this.contextPropagators = contextPropagators;
  }

  WorkflowExecution getWorkflowExecution() {
    return decisionTask.getWorkflowExecution();
  }

  WorkflowType getWorkflowType() {
    return decisionTask.getWorkflowType();
  }

  boolean isCancelRequested() {
    return cancelRequested;
  }

  void setCancelRequested(boolean flag) {
    cancelRequested = flag;
  }

  ContinueAsNewWorkflowExecutionParameters getContinueAsNewOnCompletion() {
    return continueAsNewOnCompletion;
  }

  void setContinueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters continueParameters) {
    if (continueParameters == null) {
      continueParameters = new ContinueAsNewWorkflowExecutionParameters();
    }
    //            continueParameters.setChildPolicy(startedAttributes);
    if (continueParameters.getWorkflowRunTimeoutSeconds() == 0) {
      continueParameters.setWorkflowRunTimeoutSeconds(
          startedAttributes.getWorkflowRunTimeoutSeconds());
    }
    if (continueParameters.getTaskQueue() == null) {
      continueParameters.setTaskQueue(startedAttributes.getTaskQueue().getName());
    }
    if (continueParameters.getWorkflowTaskTimeoutSeconds() == 0) {
      continueParameters.setWorkflowTaskTimeoutSeconds(
          startedAttributes.getWorkflowTaskTimeoutSeconds());
    }
    this.continueAsNewOnCompletion = continueParameters;
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
    return attributes.getWorkflowRunTimeoutSeconds();
  }

  int getWorkflowExecutionTimeoutSeconds() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return attributes.getWorkflowExecutionTimeoutSeconds();
  }

  long getWorkflowExecutionExpirationTimestampMillis() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return Duration.ofNanos(attributes.getWorkflowExecutionExpirationTimestamp()).toMillis();
  }

  long getRunStartedTimestampMillis() {
    return runStartedTimestampMillis;
  }

  int getDecisionTaskTimeoutSeconds() {
    return startedAttributes.getWorkflowTaskTimeoutSeconds();
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
