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

package com.uber.cadence.client;

import static com.uber.cadence.internal.common.InternalUtils.roundUpToSeconds;

import com.uber.cadence.ChildPolicy;
import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.internal.common.OptionsUtils;
import com.uber.cadence.workflow.WorkflowMethod;
import java.time.Duration;

public final class WorkflowOptions {

  public static WorkflowOptions merge(WorkflowMethod a, WorkflowOptions o) {
    if (a == null) {
      return new WorkflowOptions.Builder(o).validateBuildWithDefaults();
    }
    if (o == null) {
      o = new WorkflowOptions.Builder().build();
    }
    return new WorkflowOptions.Builder()
        .setWorkflowIdReusePolicy(
            OptionsUtils.merge(
                a.workflowIdReusePolicy(),
                o.getWorkflowIdReusePolicy(),
                WorkflowIdReusePolicy.class))
        .setWorkflowId(OptionsUtils.merge(a.workflowId(), o.getWorkflowId(), String.class))
        .setTaskStartToCloseTimeout(
            OptionsUtils.merge(a.taskStartToCloseTimeoutSeconds(), o.getTaskStartToCloseTimeout()))
        .setExecutionStartToCloseTimeout(
            OptionsUtils.merge(
                a.executionStartToCloseTimeoutSeconds(), o.getExecutionStartToCloseTimeout()))
        .setTaskList(OptionsUtils.merge(a.taskList(), o.getTaskList(), String.class))
        .setChildPolicy(o.getChildPolicy())
        .validateBuildWithDefaults();
  }

  public static final class Builder {

    private String workflowId;

    private WorkflowIdReusePolicy workflowIdReusePolicy;

    private Duration executionStartToCloseTimeout;

    private Duration taskStartToCloseTimeout;

    private String taskList;

    private ChildPolicy childPolicy;

    public Builder() {}

    public Builder(WorkflowOptions o) {
      if (o == null) {
        return;
      }
      this.workflowIdReusePolicy = o.workflowIdReusePolicy;
      this.workflowId = o.workflowId;
      this.taskStartToCloseTimeout = o.taskStartToCloseTimeout;
      this.executionStartToCloseTimeout = o.executionStartToCloseTimeout;
      this.taskList = o.taskList;
      this.childPolicy = o.childPolicy;
    }

    /**
     * Workflow id to use when starting. If not specified a UUID is generated. Note that it is
     * dangerous as in case of client side retries no deduplication will happen based on the
     * generated id. So prefer assigning business meaningful ids if possible.
     */
    public Builder setWorkflowId(String workflowId) {
      this.workflowId = workflowId;
      return this;
    }

    /**
     * Specifies server behavior if a completed workflow with the same id exists. Note that under no
     * conditions Cadence allows two workflows with the same domain and workflow id run
     * simultaneously.
     * <li>
     *
     *     <ul>
     *       AllowDuplicateFailedOnly is a default value. It means that workflow can start if
     *       previous run failed or was cancelled or terminated.
     * </ul>
     *
     * <ul>
     *   AllowDuplicate allows new run independently of the previous run closure status.
     * </ul>
     *
     * <ul>
     *   RejectDuplicate doesn't allow new run independently of the previous run closure status.
     * </ul>
     */
    public Builder setWorkflowIdReusePolicy(WorkflowIdReusePolicy workflowIdReusePolicy) {
      this.workflowIdReusePolicy = workflowIdReusePolicy;
      return this;
    }

    /**
     * The time after which workflow execution is automatically terminated by Cadence service. Do
     * not rely on execution timeout for business level timeouts. It is preferred to use in workflow
     * timers for this purpose.
     */
    public Builder setExecutionStartToCloseTimeout(Duration executionStartToCloseTimeout) {
      this.executionStartToCloseTimeout = executionStartToCloseTimeout;
      return this;
    }

    /**
     * Maximum execution time of a single decision task. Default is 10 seconds. Maximum accepted
     * value is 60 seconds.
     */
    public Builder setTaskStartToCloseTimeout(Duration taskStartToCloseTimeout) {
      if (roundUpToSeconds(taskStartToCloseTimeout).getSeconds() > 60) {
        throw new IllegalArgumentException(
            "TaskStartToCloseTimeout over one minute: " + taskStartToCloseTimeout);
      }
      this.taskStartToCloseTimeout = taskStartToCloseTimeout;
      return this;
    }

    /**
     * Task list to use for decision tasks. It should match a task list specified when creating a
     * {@link com.uber.cadence.worker.Worker} that hosts the workflow code.
     */
    public Builder setTaskList(String taskList) {
      this.taskList = taskList;
      return this;
    }

    /** Specifies how children of this workflow react to this workflow death. */
    public Builder setChildPolicy(ChildPolicy childPolicy) {
      this.childPolicy = childPolicy;
      return this;
    }

    public WorkflowOptions build() {
      return new WorkflowOptions(
          workflowId,
          workflowIdReusePolicy,
          executionStartToCloseTimeout,
          taskStartToCloseTimeout,
          taskList,
          childPolicy);
    }

    /**
     * Validates that all required properties are set and fills all other with default parameters.
     */
    public WorkflowOptions validateBuildWithDefaults() {
      if (executionStartToCloseTimeout == null) {
        throw new IllegalStateException(
            "Required property executionStartToCloseTimeout is not set");
      }
      if (taskList == null) {
        throw new IllegalStateException("Required property taskList is not set");
      }
      WorkflowIdReusePolicy policy = workflowIdReusePolicy;
      if (policy == null) {
        policy = WorkflowIdReusePolicy.AllowDuplicateFailedOnly;
      }
      return new WorkflowOptions(
          workflowId,
          policy,
          roundUpToSeconds(executionStartToCloseTimeout),
          roundUpToSeconds(
              taskStartToCloseTimeout, OptionsUtils.DEFAULT_TASK_START_TO_CLOSE_TIMEOUT),
          taskList,
          childPolicy);
    }
  }

  private final String workflowId;

  private final WorkflowIdReusePolicy workflowIdReusePolicy;

  private final Duration executionStartToCloseTimeout;

  private final Duration taskStartToCloseTimeout;

  private final String taskList;

  private final ChildPolicy childPolicy;

  private WorkflowOptions(
      String workflowId,
      WorkflowIdReusePolicy workflowIdReusePolicy,
      Duration executionStartToCloseTimeout,
      Duration taskStartToCloseTimeout,
      String taskList,
      ChildPolicy childPolicy) {
    this.workflowId = workflowId;
    this.workflowIdReusePolicy = workflowIdReusePolicy;
    this.executionStartToCloseTimeout = executionStartToCloseTimeout;
    this.taskStartToCloseTimeout = taskStartToCloseTimeout;
    this.taskList = taskList;
    this.childPolicy = childPolicy;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public WorkflowIdReusePolicy getWorkflowIdReusePolicy() {
    return workflowIdReusePolicy;
  }

  public Duration getExecutionStartToCloseTimeout() {
    return executionStartToCloseTimeout;
  }

  public Duration getTaskStartToCloseTimeout() {
    return taskStartToCloseTimeout;
  }

  public String getTaskList() {
    return taskList;
  }

  public ChildPolicy getChildPolicy() {
    return childPolicy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    WorkflowOptions that = (WorkflowOptions) o;

    if (workflowId != null ? !workflowId.equals(that.workflowId) : that.workflowId != null)
      return false;
    if (workflowIdReusePolicy != that.workflowIdReusePolicy) return false;
    if (executionStartToCloseTimeout != null
        ? !executionStartToCloseTimeout.equals(that.executionStartToCloseTimeout)
        : that.executionStartToCloseTimeout != null) return false;
    if (taskStartToCloseTimeout != null
        ? !taskStartToCloseTimeout.equals(that.taskStartToCloseTimeout)
        : that.taskStartToCloseTimeout != null) return false;
    if (taskList != null ? !taskList.equals(that.taskList) : that.taskList != null) return false;
    return childPolicy == that.childPolicy;
  }

  @Override
  public int hashCode() {
    int result = workflowId != null ? workflowId.hashCode() : 0;
    result = 31 * result + (workflowIdReusePolicy != null ? workflowIdReusePolicy.hashCode() : 0);
    result =
        31 * result
            + (executionStartToCloseTimeout != null ? executionStartToCloseTimeout.hashCode() : 0);
    result =
        31 * result + (taskStartToCloseTimeout != null ? taskStartToCloseTimeout.hashCode() : 0);
    result = 31 * result + (taskList != null ? taskList.hashCode() : 0);
    result = 31 * result + (childPolicy != null ? childPolicy.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return "WorkflowOptions{"
        + "workflowId='"
        + workflowId
        + '\''
        + ", workflowIdReusePolicy="
        + workflowIdReusePolicy
        + ", executionStartToCloseTimeout="
        + executionStartToCloseTimeout
        + ", taskStartToCloseTimeout="
        + taskStartToCloseTimeout
        + ", taskList='"
        + taskList
        + '\''
        + ", childPolicy="
        + childPolicy
        + '}';
  }
}
