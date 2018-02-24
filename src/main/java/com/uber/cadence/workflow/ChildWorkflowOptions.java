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
package com.uber.cadence.workflow;

import com.uber.cadence.ChildPolicy;
import com.uber.cadence.WorkflowIdReusePolicy;

public final class ChildWorkflowOptions {

    public static final class Builder {

        private String domain;

        private String workflowId;

        private WorkflowIdReusePolicy workflowIdReusePolicy = WorkflowIdReusePolicy.AllowDuplicateFailedOnly;

        private int executionStartToCloseTimeoutSeconds;

        private int taskStartToCloseTimeoutSeconds = 10;

        private String taskList;

        private ChildPolicy childPolicy;

        private RetryOptions retryOptions;

        public Builder() {
        }

        public Builder(ChildWorkflowOptions source) {
            this.domain = source.getDomain();
            this.workflowId = source.getWorkflowId();
            this.workflowIdReusePolicy = source.getWorkflowIdReusePolicy();
            this.executionStartToCloseTimeoutSeconds = source.getExecutionStartToCloseTimeoutSeconds();
            this.taskStartToCloseTimeoutSeconds = source.getTaskStartToCloseTimeoutSeconds();
            this.taskList = source.getTaskList();
            this.childPolicy = source.getChildPolicy();
            this.retryOptions = source.getRetryOptions();
        }

        /**
         * Specify domain in which workflow should be started.
         * <p>
         * TODO: Resolve conflict with WorkflowClient domain.
         * </p>
         */
        public Builder setDomain(String domain) {
            this.domain = domain;
            return this;
        }

        /**
         * Workflow id to use when starting. If not specified a UUID is generated. Note that it is dangerous
         * as in case of client side retries no deduplication will happen based on the generated id.
         * So prefer assigning business meaningful ids if possible.
         */
        public Builder setWorkflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        /**
         * Specifies server behavior if a completed workflow with the same id exists.
         * Note that under no conditions Cadence allows two workflows with the same domain and workflow id run simultaneously.
         * <li>
         * <ul>AllowDuplicateFailedOnly is a default value. It means that workflow can start if previous run failed or was cancelled or terminated.</ul>
         * <ul>AllowDuplicate allows new run independently of the previous run closure status.</ul>
         * <ul>RejectDuplicate doesn't allow new run independently of the previous run closure status.</ul>
         * </li>
         */
        public Builder setWorkflowIdReusePolicy(WorkflowIdReusePolicy workflowIdReusePolicy) {
            this.workflowIdReusePolicy = workflowIdReusePolicy;
            return this;
        }

        /**
         * The time after which workflow execution is automatically terminated by Cadence service.
         * Do not rely on execution timeout for business level timeouts. It is preferred to use
         * in workflow timers for this purpose.
         */
        public Builder setExecutionStartToCloseTimeoutSeconds(int executionStartToCloseTimeoutSeconds) {
            this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
            return this;
        }

        /**
         * Maximum execution time of a single decision task. Default is 10 seconds.
         * Maximum accepted value is 60 seconds.
         */
        public Builder setTaskStartToCloseTimeoutSeconds(int taskStartToCloseTimeoutSeconds) {
            if (taskStartToCloseTimeoutSeconds > 60) {
                throw new IllegalArgumentException("TaskStartToCloseTimeout over one minute: " + taskStartToCloseTimeoutSeconds);
            }
            this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
            return this;
        }

        /**
         * Task list to use for decision tasks. It should match a task list specified when creating
         * a {@link com.uber.cadence.worker.Worker} that hosts the workflow code.
         */
        public Builder setTaskList(String taskList) {
            this.taskList = taskList;
            return this;
        }

        /**
         * Specifies how children of this workflow react to this workflow death.
         */
        public Builder setChildPolicy(ChildPolicy childPolicy) {
            this.childPolicy = childPolicy;
            return this;
        }

        /**
         * RetryOptions that define how child workflow is retried in case of failure.
         * Default is null which is no reties.
         */
        public Builder setRetryOptions(RetryOptions retryOptions) {
            this.retryOptions = retryOptions;
            return this;
        }

        public ChildWorkflowOptions build() {
            return new ChildWorkflowOptions(domain, workflowId, workflowIdReusePolicy, executionStartToCloseTimeoutSeconds,
                    taskStartToCloseTimeoutSeconds, taskList, retryOptions, childPolicy);
        }
    }

    private final String domain;

    private final String workflowId;

    private final WorkflowIdReusePolicy workflowIdReusePolicy;

    private final int executionStartToCloseTimeoutSeconds;

    private final int taskStartToCloseTimeoutSeconds;

    private final String taskList;

    private final RetryOptions retryOptions;

    private final ChildPolicy childPolicy;

    private ChildWorkflowOptions(String domain, String workflowId, WorkflowIdReusePolicy workflowIdReusePolicy,
                                 int executionStartToCloseTimeoutSeconds, int taskStartToCloseTimeoutSeconds, String taskList, RetryOptions retryOptions, ChildPolicy childPolicy) {
        this.domain = domain;
        this.workflowId = workflowId;
        this.workflowIdReusePolicy = workflowIdReusePolicy;
        this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
        this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
        this.taskList = taskList;
        this.retryOptions = retryOptions;
        this.childPolicy = childPolicy;
    }

    public String getDomain() {
        return domain;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public WorkflowIdReusePolicy getWorkflowIdReusePolicy() {
        return workflowIdReusePolicy;
    }

    public int getExecutionStartToCloseTimeoutSeconds() {
        return executionStartToCloseTimeoutSeconds;
    }

    public int getTaskStartToCloseTimeoutSeconds() {
        return taskStartToCloseTimeoutSeconds;
    }

    public String getTaskList() {
        return taskList;
    }

    public ChildPolicy getChildPolicy() {
        return childPolicy;
    }

    public RetryOptions getRetryOptions() {
        return retryOptions;
    }

    @Override
    public String toString() {
        return "ChildWorkflowOptions{" +
                "domain='" + domain + '\'' +
                ", workflowId='" + workflowId + '\'' +
                ", workflowIdReusePolicy=" + workflowIdReusePolicy +
                ", executionStartToCloseTimeoutSeconds=" + executionStartToCloseTimeoutSeconds +
                ", taskStartToCloseTimeoutSeconds=" + taskStartToCloseTimeoutSeconds +
                ", taskList='" + taskList + '\'' +
                ", retryOptions=" + retryOptions +
                ", childPolicy=" + childPolicy +
                '}';
    }
}
