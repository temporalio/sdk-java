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

import io.temporal.proto.execution.WorkflowExecution;
import io.temporal.proto.execution.WorkflowExecutionStatus;
import io.temporal.proto.query.QueryRejectCondition;

public final class WorkflowQueryRejectedException extends WorkflowQueryException {

  private final QueryRejectCondition queryRejectCondition;
  private final WorkflowExecutionStatus workflowExecutionStatus;

  public WorkflowQueryRejectedException(
      WorkflowExecution execution,
      QueryRejectCondition queryRejectCondition,
      WorkflowExecutionStatus workflowExecutionStatus) {
    super(
        execution,
        "Query invoked with "
            + queryRejectCondition
            + " reject condition. The workflow execution status is "
            + workflowExecutionStatus);
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
