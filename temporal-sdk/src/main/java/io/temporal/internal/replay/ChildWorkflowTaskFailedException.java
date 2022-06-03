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

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.failure.v1.Failure;

/** Internal. Do not catch or throw by application level code. */
@SuppressWarnings("serial")
public class ChildWorkflowTaskFailedException extends RuntimeException {

  private final long eventId;

  private final WorkflowExecution workflowExecution;

  private final WorkflowType workflowType;

  private final RetryState retryState;

  private final Failure failure;

  public ChildWorkflowTaskFailedException(
      long eventId,
      WorkflowExecution workflowExecution,
      WorkflowType workflowType,
      RetryState retryState,
      Failure failure) {
    this.eventId = eventId;
    this.workflowExecution = workflowExecution;
    this.workflowType = workflowType;
    this.retryState = retryState;
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

  public RetryState getRetryState() {
    return retryState;
  }
}
