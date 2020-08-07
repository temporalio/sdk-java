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

package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.internal.common.RpcRetryOptions;
import java.time.Duration;

/**
 * Interface of an activity task handler.
 *
 * @author fateev
 */
public interface ActivityTaskHandler {

  final class Result {

    private final String activityId;
    private final RespondActivityTaskCompletedRequest taskCompleted;
    private final TaskFailedResult taskFailed;
    private final RespondActivityTaskCanceledRequest taskCancelled;
    private final RpcRetryOptions requestRetryOptions;
    private int attempt;
    private Duration backoff;

    @Override
    public String toString() {
      return "Result{"
          + "activityId='"
          + activityId
          + '\''
          + ", taskCompleted="
          + taskCompleted
          + ", taskFailed="
          + taskFailed
          + ", taskCancelled="
          + taskCancelled
          + ", attempt="
          + attempt
          + ", backoff="
          + backoff
          + '}';
    }

    public static class TaskFailedResult {
      private final RespondActivityTaskFailedRequest taskFailedRequest;
      private final Throwable failure;

      public TaskFailedResult(
          RespondActivityTaskFailedRequest taskFailedRequest, Throwable failure) {
        this.taskFailedRequest = taskFailedRequest;
        this.failure = failure;
      }

      public RespondActivityTaskFailedRequest getTaskFailedRequest() {
        return taskFailedRequest;
      }

      public Throwable getFailure() {
        return failure;
      }
    }

    /**
     * Only zero (manual activity completion) or one request is allowed. Task token and identity
     * fields shouldn't be filled in. Retry options are the service call. These options override the
     * default ones set on the activity worker.
     */
    public Result(
        String activityId,
        RespondActivityTaskCompletedRequest taskCompleted,
        TaskFailedResult taskFailed,
        RespondActivityTaskCanceledRequest taskCancelled,
        RpcRetryOptions requestRetryOptions) {
      this.activityId = activityId;
      this.taskCompleted = taskCompleted;
      this.taskFailed = taskFailed;
      this.taskCancelled = taskCancelled;
      this.requestRetryOptions = requestRetryOptions;
    }

    public String getActivityId() {
      return activityId;
    }

    public RespondActivityTaskCompletedRequest getTaskCompleted() {
      return taskCompleted;
    }

    public TaskFailedResult getTaskFailed() {
      return taskFailed;
    }

    public RespondActivityTaskCanceledRequest getTaskCancelled() {
      return taskCancelled;
    }

    public RpcRetryOptions getRequestRetryOptions() {
      return requestRetryOptions;
    }

    public void setAttempt(int attempt) {
      this.attempt = attempt;
    }

    public int getAttempt() {
      return attempt;
    }

    public void setBackoff(Duration backoff) {
      this.backoff = backoff;
    }

    public Duration getBackoff() {
      return backoff;
    }
  }

  /**
   * The implementation should be called when a polling activity worker receives a new activity
   * task. This method shouldn't throw any exception unless there is a need to not reply to the
   * task.
   *
   * @param activityTask activity task which is response to PollActivityTaskQueue call.
   * @return One of the possible activity task replies.
   */
  Result handle(
      PollActivityTaskQueueResponse activityTask, Scope metricsScope, boolean isLocalActivity);

  /** True if this handler handles at least one activity type. */
  boolean isAnyTypeSupported();
}
