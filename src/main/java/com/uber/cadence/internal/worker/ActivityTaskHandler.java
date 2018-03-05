/*
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
package com.uber.cadence.internal.worker;

import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.RespondActivityTaskCanceledRequest;
import com.uber.cadence.RespondActivityTaskCompletedRequest;
import com.uber.cadence.RespondActivityTaskFailedRequest;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.common.RetryOptions;

/**
 * Interface of an activity task handler.
 *
 * @author fateev
 */
public interface ActivityTaskHandler {

    final class Result {

        private final RespondActivityTaskCompletedRequest taskCompleted;
        private final RespondActivityTaskFailedRequest taskFailed;
        private final RespondActivityTaskCanceledRequest taskCancelled;
        private final RetryOptions requestRetryOptions;


        /**
         * Only zero (manual activity completion) or one request is allowed.
         * Task token and identity fields shouldn't be filled in.
         * Retry options are the service call. These options override the default ones
         * set on the activity worker.
         */
        public Result(RespondActivityTaskCompletedRequest taskCompleted,
                      RespondActivityTaskFailedRequest taskFailed,
                      RespondActivityTaskCanceledRequest taskCancelled,
                      RetryOptions requestRetryOptions) {
            this.taskCompleted = taskCompleted;
            this.taskFailed = taskFailed;
            this.taskCancelled = taskCancelled;
            this.requestRetryOptions = requestRetryOptions;
        }

        public RespondActivityTaskCompletedRequest getTaskCompleted() {
            return taskCompleted;
        }

        public RespondActivityTaskFailedRequest getTaskFailed() {
            return taskFailed;
        }

        public RespondActivityTaskCanceledRequest getTaskCancelled() {
            return taskCancelled;
        }

        public RetryOptions getRequestRetryOptions() {
            return requestRetryOptions;
        }
    }

    /**
     * The implementation should be called when a polling activity worker receives a
     * new activity task. This method shouldn't throw any exception unless there is a need to not
     * reply to the task.
     *
     * @param activityTask activity task which is response to PollForActivityTask call.
     * @return One of the possible decision task replies.
     */
    Result handle(WorkflowService.Iface service, String domain, PollForActivityTaskResponse activityTask);

    /**
     * True if this handler handles at least one activity type.
     */
    boolean isAnyTypeSupported();
}
