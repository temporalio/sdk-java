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

package io.temporal.internal.worker.workflow;

import io.temporal.api.common.v1.WorkflowExecution;

public class ExecutionInfoStrategy implements WorkflowMethodThreadNameStrategy {
  public static final ExecutionInfoStrategy INSTANCE = new ExecutionInfoStrategy();
  private static final int WORKFLOW_ID_TRIM_LENGTH = 50;

  private ExecutionInfoStrategy() {}

  @Override
  public String createThreadName(WorkflowExecution workflowExecution) {
    String workflowId = workflowExecution.getWorkflowId();
    String trimmedWorkflowId =
        workflowId.substring(0, Math.min(WORKFLOW_ID_TRIM_LENGTH, workflowId.length()) - 1);
    return WORKFLOW_MAIN_THREAD_PREFIX
        + "-"
        + trimmedWorkflowId
        + "-"
        + workflowExecution.getRunId();
  }
}
