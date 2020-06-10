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

import io.temporal.client.WorkflowException;
import io.temporal.proto.common.RetryStatus;
import io.temporal.proto.common.WorkflowExecution;

public final class ChildWorkflowException extends WorkflowException {

  private final long initiatedEventId;
  private final long startedEventId;
  private final String namespace;
  private final RetryStatus retryStatus;

  public ChildWorkflowException(
      long initiatedEventId,
      long startedEventId,
      String workflowType,
      WorkflowExecution workflowExecution,
      String namespace,
      RetryStatus retryStatus,
      Throwable cause) {
    super(workflowExecution, workflowType, cause);
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

  @Override
  public String toString() {
    return "ChildWorkflowException{"
        + "execution="
        + getExecution()
        + ", workflowType='"
        + getWorkflowType()
        + '\''
        + "initiatedEventId="
        + initiatedEventId
        + ", startedEventId="
        + startedEventId
        + ", namespace='"
        + namespace
        + '\''
        + ", retryStatus="
        + retryStatus
        + '}';
  }
}
