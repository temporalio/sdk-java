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

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.workflow.WorkflowInfo;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class WorkflowInfoImpl implements WorkflowInfo {

  public enum SearchAttribute {
    ExecutionStatus,
    CloseTime,
    CustomBoolField,
    CustomDatetimeField,
    CustomNamespace,
    CustomDoubleField,
    CustomIntField,
    CustomKeywordField,
    CustomStringField,
    NamespaceId,
    ExecutionTime,
    HistoryLength,
    RunId,
    StartTime,
    TaskQueue,
    WorkflowId,
    WorkflowType;
  }

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
  @Deprecated
  public SearchAttributes getSearchAttributes() {
    return context.getSearchAttributes();
  }

  @Override
  public Map<String, Object> getSearchAttributesMap() {
    Map<String, Payload> serializedSearchAttributes =
        context.getSearchAttributes().getIndexedFieldsMap();
    Map<String, Object> searchAttributes = new HashMap<>();
    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();

    for (String searchAttribute : serializedSearchAttributes.keySet()) {
      SearchAttribute attribute = SearchAttribute.valueOf(searchAttribute);
      Payload payload = serializedSearchAttributes.get(searchAttribute);
      String stringValue = converter.fromPayload(payload, String.class, String.class);
      switch (attribute) {
        case CustomBoolField:
          searchAttributes.put(searchAttribute, Boolean.parseBoolean(stringValue));
          break;
        case CustomDatetimeField:
          searchAttributes.put(searchAttribute, LocalDateTime.parse(stringValue));
          break;
        case CustomDoubleField:
          searchAttributes.put(searchAttribute, Double.parseDouble(stringValue));
          break;
        case CloseTime:
        case CustomIntField:
        case ExecutionStatus:
        case ExecutionTime:
        case HistoryLength:
        case StartTime:
          searchAttributes.put(searchAttribute, Integer.parseInt(stringValue));
          break;
        case CustomKeywordField:
        case CustomNamespace:
        case CustomStringField:
        case NamespaceId:
        case RunId:
        case TaskQueue:
        case WorkflowId:
        case WorkflowType:
          searchAttributes.put(searchAttribute, stringValue);
          break;
      }
    }
    return searchAttributes;
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
}
