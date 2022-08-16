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

package io.temporal.internal.worker;

import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.serviceclient.RpcRetryOptions;

/**
 * Interface of workflow task handlers.
 *
 * @author fateev, suskin
 */
public interface WorkflowTaskHandler {

  final class Result {
    private final String workflowType;
    private final RespondWorkflowTaskCompletedRequest taskCompleted;
    private final RespondWorkflowTaskFailedRequest taskFailed;
    private final RespondQueryTaskCompletedRequest queryCompleted;
    private final RpcRetryOptions requestRetryOptions;
    private final boolean completionCommand;

    public Result(
        String workflowType,
        RespondWorkflowTaskCompletedRequest taskCompleted,
        RespondWorkflowTaskFailedRequest taskFailed,
        RespondQueryTaskCompletedRequest queryCompleted,
        RpcRetryOptions requestRetryOptions,
        boolean completionCommand) {
      this.workflowType = workflowType;
      this.taskCompleted = taskCompleted;
      this.taskFailed = taskFailed;
      this.queryCompleted = queryCompleted;
      this.requestRetryOptions = requestRetryOptions;
      this.completionCommand = completionCommand;
    }

    public RespondWorkflowTaskCompletedRequest getTaskCompleted() {
      return taskCompleted;
    }

    public RespondWorkflowTaskFailedRequest getTaskFailed() {
      return taskFailed;
    }

    public RespondQueryTaskCompletedRequest getQueryCompleted() {
      return queryCompleted;
    }

    public RpcRetryOptions getRequestRetryOptions() {
      return requestRetryOptions;
    }

    public boolean isCompletionCommand() {
      return completionCommand;
    }

    @Override
    public String toString() {
      return "Result{"
          + "workflowType='"
          + workflowType
          + '\''
          + ", taskCompleted="
          + taskCompleted
          + ", taskFailed="
          + taskFailed
          + ", queryCompleted="
          + queryCompleted
          + ", requestRetryOptions="
          + requestRetryOptions
          + ", completionCommand="
          + completionCommand
          + '}';
    }

    public String getWorkflowType() {
      return workflowType;
    }
  }

  /**
   * Handles a single workflow task
   *
   * @param workflowTask The workflow task to handle.
   * @param workflowTaskStartTimeNs
   * @return One of the possible workflow task replies: RespondWorkflowTaskCompletedRequest,
   *     RespondQueryTaskCompletedRequest, RespondWorkflowTaskFailedRequest
   * @throws Exception an original exception or error if the processing should be just abandoned
   *     without replying to the server
   */
  Result handleWorkflowTask(
      PollWorkflowTaskQueueResponse workflowTask, long workflowTaskStartTimeNs) throws Exception;

  /** True if this handler handles at least one workflow type. */
  boolean isAnyTypeSupported();
}
