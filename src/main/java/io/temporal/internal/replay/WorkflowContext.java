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

import com.google.protobuf.ByteString;
import io.temporal.common.context.ContextPropagator;
import io.temporal.proto.common.Header;
import io.temporal.proto.common.SearchAttributes;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.common.WorkflowExecutionStartedEventAttributes;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponseOrBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class WorkflowContext {

  private final PollForDecisionTaskResponseOrBuilder decisionTask;
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
      List<ContextPropagator> contextPropagators) {
    this.namespace = namespace;
    this.decisionTask = decisionTask;
    this.startedAttributes = startedAttributes;
    this.currentRunId = startedAttributes.getOriginalExecutionRunId();
    if (startedAttributes.hasSearchAttributes()) {
      this.searchAttributes = startedAttributes.getSearchAttributes().toBuilder();
    }
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
    if (continueParameters.getExecutionStartToCloseTimeoutSeconds() == 0) {
      continueParameters.setExecutionStartToCloseTimeoutSeconds(
          startedAttributes.getExecutionStartToCloseTimeoutSeconds());
    }
    if (continueParameters.getTaskList() == null) {
      continueParameters.setTaskList(startedAttributes.getTaskList().getName());
    }
    if (continueParameters.getTaskStartToCloseTimeoutSeconds() == 0) {
      continueParameters.setTaskStartToCloseTimeoutSeconds(
          startedAttributes.getTaskStartToCloseTimeoutSeconds());
    }
    this.continueAsNewOnCompletion = continueParameters;
  }

  String getContinuedExecutionRunId() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    String runId = attributes.getContinuedExecutionRunId();
    return runId.isEmpty() ? null : runId;
  }

  WorkflowExecution getParentWorkflowExecution() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return attributes.hasParentWorkflowExecution() ? attributes.getParentWorkflowExecution() : null;
  }

  int getExecutionStartToCloseTimeoutSeconds() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return attributes.getExecutionStartToCloseTimeoutSeconds();
  }

  int getDecisionTaskTimeoutSeconds() {
    return startedAttributes.getTaskStartToCloseTimeoutSeconds();
  }

  String getTaskList() {
    WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
    return attributes.getTaskList().getName();
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

    Map<String, byte[]> headerData = new HashMap<>();
    for (Map.Entry<String, ByteString> pair : headers.getFieldsMap().entrySet()) {
      headerData.put(pair.getKey(), pair.getValue().toByteArray());
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
    for (Map.Entry<String, ByteString> pair : searchAttributes.getIndexedFieldsMap().entrySet()) {
      this.searchAttributes.putIndexedFields(pair.getKey(), pair.getValue());
    }
    if (searchAttributes.getIndexedFieldsCount() == 0) {
      this.searchAttributes = null;
    }
  }
}
