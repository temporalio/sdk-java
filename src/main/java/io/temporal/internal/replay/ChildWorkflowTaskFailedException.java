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

import io.temporal.common.v1.WorkflowExecution;
import io.temporal.common.v1.WorkflowType;
import io.temporal.enums.v1.RetryStatus;
import io.temporal.failure.v1.Failure;

/** Internal. Do not catch or throw by application level code. */
@SuppressWarnings("serial")
public class ChildWorkflowTaskFailedException extends RuntimeException {

  private final long eventId;

  private final WorkflowExecution workflowExecution;

  private final WorkflowType workflowType;

  private final RetryStatus retryStatus;

  private final Failure failure;

  public ChildWorkflowTaskFailedException(
      long eventId,
      WorkflowExecution workflowExecution,
      WorkflowType workflowType,
      RetryStatus retryStatus,
      Failure failure) {
    this.eventId = eventId;
    this.workflowExecution = workflowExecution;
    this.workflowType = workflowType;
    this.retryStatus = retryStatus;
    this.failure = failure;
  }

  public long getEventId() {
    return eventId;
  }

  public WorkflowExecution getWorkflowExecution() {
    return workflowExecution;
  }

  public WorkflowType getWorkflowType() {
    return workflowType;
  }

  public Failure getFailure() {
    return failure;
  }

  public RetryStatus getRetryStatus() {
    return retryStatus;
  }
}
