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

package io.temporal.workflow;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowException;

/** Exception used to communicate failure of a request to signal an external workflow. */
@SuppressWarnings("serial")
public final class SignalExternalWorkflowException extends WorkflowException {

  public SignalExternalWorkflowException(WorkflowExecution execution, String workflowType) {
    super(getMessage(execution, workflowType), execution, workflowType, null);
  }

  public static String getMessage(WorkflowExecution execution, String workflowType) {
    return "message='Open execution not found', workflowId='"
        + execution.getWorkflowId()
        + "', runId='"
        + execution.getRunId()
        + "'"
        + (workflowType == null ? "" : "', workflowType='" + workflowType + '\'');
  }
}
