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

package io.temporal.failure;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.RetryState;
import java.util.Objects;

public final class ChildWorkflowFailure extends TemporalFailure {

  private final long initiatedEventId;
  private final long startedEventId;
  private final String namespace;
  private final RetryState retryState;
  private final WorkflowExecution execution;
  private final String workflowType;

  public ChildWorkflowFailure(
      long initiatedEventId,
      long startedEventId,
      String workflowType,
      WorkflowExecution execution,
      String namespace,
      RetryState retryState,
      Throwable cause) {
    super(
        getMessage(
            execution, workflowType, initiatedEventId, startedEventId, namespace, retryState),
        null,
        cause);
    this.execution = Objects.requireNonNull(execution);
    this.workflowType = Objects.requireNonNull(workflowType);
    this.initiatedEventId = initiatedEventId;
    this.startedEventId = startedEventId;
    this.namespace = namespace;
    this.retryState = retryState;
  }

  public long getInitiatedEventId() {
    return initiatedEventId;
  }

  public long getStartedEventId() {
    return startedEventId;
  }

  public String getNamespace() {
    return namespace;
  }

  public RetryState getRetryState() {
    return retryState;
  }

  public WorkflowExecution getExecution() {
    return execution;
  }

  public String getWorkflowType() {
    return workflowType;
  }

  public static String getMessage(
      WorkflowExecution execution,
      String workflowType,
      long initiatedEventId,
      long startedEventId,
      String namespace,
      RetryState retryState) {
    return "workflowId='"
        + execution.getWorkflowId()
        + '\''
        + ", runId='"
        + execution.getRunId()
        + '\''
        + ", workflowType='"
        + workflowType
        + '\''
        + ", initiatedEventId="
        + initiatedEventId
        + ", startedEventId="
        + startedEventId
        + ", namespace='"
        + namespace
        + '\''
        + ", retryState="
        + retryState;
  }
}
