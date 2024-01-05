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

import com.uber.m3.tally.Scope;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;

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
    private final RespondActivityTaskCanceledRequest taskCanceled;
    private final boolean manualCompletion;

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
          + ", taskCanceled="
          + taskCanceled
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
        RespondActivityTaskCanceledRequest taskCanceled,
        boolean manualCompletion) {
      this.activityId = activityId;
      this.taskCompleted = taskCompleted;
      this.taskFailed = taskFailed;
      this.taskCanceled = taskCanceled;
      this.manualCompletion = manualCompletion;
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

    public RespondActivityTaskCanceledRequest getTaskCanceled() {
      return taskCanceled;
    }

    public boolean isManualCompletion() {
      return manualCompletion;
    }
  }

  void registerActivityImplementations(Object[] activitiesImplementation);

  /**
   * The implementation should be called when a polling activity worker receives a new activity
   * task. This method shouldn't throw any Throwables unless there is a need to not reply to the
   * task.
   *
   * @param activityTask activity task which is response to PollActivityTaskQueue call.
   * @return One of the possible activity task replies.
   */
  Result handle(ActivityTask activityTask, Scope metricsScope, boolean isLocalActivity);

  /** True if this handler handles at least one activity type. */
  boolean isAnyTypeSupported();

  /**
   * @param activityType activity type name
   * @return true if an activity implementation with {@code activityType} name is registered or a
   *     dynamic activity implementation is registered.
   */
  boolean isTypeSupported(String activityType);
}
