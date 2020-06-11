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

package io.temporal.client;

import io.temporal.failure.TemporalException;
import io.temporal.proto.common.WorkflowExecution;
import java.util.Objects;

/** Base exception for all workflow failures. */
public abstract class WorkflowException extends TemporalException {

  private final WorkflowExecution execution;
  private final String workflowType;

  protected WorkflowException(WorkflowExecution execution, String workflowType, Throwable cause) {
    super(getMessage(execution, workflowType), cause);
    this.execution = Objects.requireNonNull(execution);
    this.workflowType = workflowType;
  }

  protected WorkflowException(
      String message, WorkflowExecution execution, String workflowType, Throwable cause) {
    super(message, cause);
    this.execution = Objects.requireNonNull(execution);
    this.workflowType = workflowType;
  }

  public WorkflowExecution getExecution() {
    return execution;
  }

  /** Not always known and might return null. */
  public String getWorkflowType() {
    return workflowType;
  }

  public static String getMessage(WorkflowExecution execution, String workflowType) {
    return "workflowId='"
        + execution.getWorkflowId()
        + "', runId='"
        + execution.getRunId()
        + (workflowType == null ? "" : "', workflowType='" + workflowType + '\'')
        + '}';
  }
}
