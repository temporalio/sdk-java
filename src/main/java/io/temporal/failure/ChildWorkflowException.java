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

package io.temporal.failure;

import io.temporal.proto.common.RetryStatus;
import io.temporal.proto.common.WorkflowExecution;
import java.util.Objects;

public final class ChildWorkflowException extends TemporalFailure {

  private final long initiatedEventId;
  private final long startedEventId;
  private final String namespace;
  private final RetryStatus retryStatus;
  private final WorkflowExecution execution;
  private final String workflowType;

  public ChildWorkflowException(
      long initiatedEventId,
      long startedEventId,
      String workflowType,
      WorkflowExecution execution,
      String namespace,
      RetryStatus retryStatus,
      Throwable cause) {
    super(
        getMessage(
            execution, workflowType, initiatedEventId, startedEventId, namespace, retryStatus),
        null,
        cause);
    this.execution = Objects.requireNonNull(execution);
    this.workflowType = Objects.requireNonNull(workflowType);
    this.initiatedEventId = initiatedEventId;
    this.startedEventId = startedEventId;
    this.namespace = namespace;
    this.retryStatus = retryStatus;
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

  public RetryStatus getRetryStatus() {
    return retryStatus;
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
      RetryStatus retryStatus) {
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
        + ", retryStatus="
        + retryStatus;
  }
}
