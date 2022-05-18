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

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.QueryRejectCondition;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;

/**
 * If workflow gets rejected based on {@link QueryRejectCondition} specified on {@link
 * WorkflowClientOptions#getQueryRejectCondition()}
 */
public final class WorkflowQueryConditionallyRejectedException
    extends WorkflowQueryRejectedException {

  private final QueryRejectCondition queryRejectCondition;
  private final WorkflowExecutionStatus workflowExecutionStatus;

  public WorkflowQueryConditionallyRejectedException(
      WorkflowExecution execution,
      String workflowType,
      QueryRejectCondition queryRejectCondition,
      WorkflowExecutionStatus workflowExecutionStatus,
      Throwable cause) {
    super(execution, workflowType, cause);
    this.queryRejectCondition = queryRejectCondition;
    this.workflowExecutionStatus = workflowExecutionStatus;
  }

  public QueryRejectCondition getQueryRejectCondition() {
    return queryRejectCondition;
  }

  public WorkflowExecutionStatus getWorkflowExecutionStatus() {
    return workflowExecutionStatus;
  }
}
