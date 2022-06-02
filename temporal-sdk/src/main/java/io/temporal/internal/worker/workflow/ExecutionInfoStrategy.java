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

package io.temporal.internal.worker.workflow;

import com.google.common.base.Preconditions;
import io.temporal.api.common.v1.WorkflowExecution;
import javax.annotation.Nonnull;

public class ExecutionInfoStrategy implements WorkflowMethodThreadNameStrategy {
  public static final ExecutionInfoStrategy INSTANCE = new ExecutionInfoStrategy();
  private static final int WORKFLOW_ID_TRIM_LENGTH = 50;
  private static final String TRIM_MARKER = "...";

  private ExecutionInfoStrategy() {}

  @Nonnull
  @Override
  public String createThreadName(@Nonnull WorkflowExecution workflowExecution) {
    Preconditions.checkNotNull(workflowExecution, "workflowExecution");
    String workflowId = workflowExecution.getWorkflowId();

    String trimmedWorkflowId =
        workflowId.length() > WORKFLOW_ID_TRIM_LENGTH
            ?
            // add a ' at the end to explicitly show that the id was trimmed
            workflowId.substring(0, WORKFLOW_ID_TRIM_LENGTH) + TRIM_MARKER
            : workflowId;

    return WORKFLOW_MAIN_THREAD_PREFIX
        + "-"
        + trimmedWorkflowId
        + "-"
        + workflowExecution.getRunId();
  }
}
