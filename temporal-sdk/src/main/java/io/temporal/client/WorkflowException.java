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

package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.failure.TemporalException;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Base exception for all workflow failures. */
public abstract class WorkflowException extends TemporalException {

  private final WorkflowExecution execution;
  private final Optional<String> workflowType;

  protected WorkflowException(
      @Nonnull WorkflowExecution execution, String workflowType, Throwable cause) {
    super(getMessage(execution, workflowType), cause);
    this.execution = Objects.requireNonNull(execution);
    this.workflowType = Optional.ofNullable(workflowType);
  }

  protected WorkflowException(
      String message, WorkflowExecution execution, String workflowType, Throwable cause) {
    super(message, cause);
    this.execution = Objects.requireNonNull(execution);
    this.workflowType = Optional.ofNullable(workflowType);
  }

  public WorkflowExecution getExecution() {
    return execution;
  }

  public Optional<String> getWorkflowType() {
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
