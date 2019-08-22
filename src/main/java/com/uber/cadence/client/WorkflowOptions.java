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

import static com.uber.cadence.internal.common.OptionsUtils.roundUpToSeconds;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.google.common.base.Strings;
import com.uber.cadence.ChildPolicy;
import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.common.CronSchedule;
import com.uber.cadence.common.MethodRetry;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.internal.common.OptionsUtils;
import com.uber.cadence.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public final class WorkflowOptions {

  public static WorkflowOptions merge(
      WorkflowMethod a, MethodRetry methodRetry, CronSchedule cronSchedule, WorkflowOptions o) {
    if (a == null) {
      return new WorkflowOptions.Builder(o).validateBuildWithDefaults();
    }
    if (o == null) {
      o = new WorkflowOptions.Builder().build();
    }
    String cronAnnotation = cronSchedule == null ? "" : cronSchedule.value();
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
        .setRetryOptions(RetryOptions.merge(methodRetry, o.getRetryOptions()))
        .setCronSchedule(OptionsUtils.merge(cronAnnotation, o.getCronSchedule(), String.class))
        .setMemo(o.getMemo())
        .setSearchAttributes(o.getSearchAttributes())
        .validateBuildWithDefaults();
  }

  public static final class Builder {

    private String workflowId;

    private WorkflowIdReusePolicy workflowIdReusePolicy;

    private Duration executionStartToCloseTimeout;

    private Duration taskStartToCloseTimeout;

    private String taskList;

    private ChildPolicy childPolicy;

    private RetryOptions retryOptions;

    private String cronSchedule;

    private Map<String, Object> memo;

    private Map<String, Object> searchAttributes;

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
      this.retryOptions = o.retryOptions;
      this.cronSchedule = o.cronSchedule;
      this.memo = o.memo;
      this.searchAttributes = o.searchAttributes;
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

    public Builder setRetryOptions(RetryOptions retryOptions) {
      this.retryOptions = retryOptions;
      return this;
    }

    public Builder setCronSchedule(String cronSchedule) {
      this.cronSchedule = cronSchedule;
      return this;
    }

    /**
     * Specifies additional non-indexed information in result of list workflow. The type of value
     * can be any object that are serializable by {@link com.uber.cadence.converter.DataConverter}
     */
    public Builder setMemo(Map<String, Object> memo) {
      this.memo = memo;
      return this;
    }

    /**
     * Specifies additional indexed information in result of list workflow. The type of value should
     * be basic type such as: String, Integer, Boolean, Double，LocalDateTime
     */
    public Builder setSearchAttributes(Map<String, Object> searchAttributes) {
      this.searchAttributes = searchAttributes;
      return this;
    }

    public WorkflowOptions build() {
      return new WorkflowOptions(
          workflowId,
          workflowIdReusePolicy,
          executionStartToCloseTimeout,
          taskStartToCloseTimeout,
          taskList,
          childPolicy,
          retryOptions,
          cronSchedule,
          memo,
          searchAttributes);
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
        throw new IllegalArgumentException("Required property taskList is not set");
      }
      WorkflowIdReusePolicy policy = workflowIdReusePolicy;
      if (policy == null) {
        policy = WorkflowIdReusePolicy.AllowDuplicateFailedOnly;
      }
      if (retryOptions != null) {
        if (retryOptions.getInitialInterval() == null) {
          throw new IllegalArgumentException(
              "RetryOptions missing required initialInterval property");
        }
        if (retryOptions.getExpiration() == null && retryOptions.getMaximumAttempts() == 0) {
          throw new IllegalArgumentException(
              "RetryOptions must specify either expiration or maximum attempts");
        }
      }

      if (!Strings.isNullOrEmpty(cronSchedule)) {
        CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
        CronParser parser = new CronParser(cronDefinition);
        Cron cron = parser.parse(cronSchedule);
        cron.validate();
      }

      return new WorkflowOptions(
          workflowId,
          policy,
          roundUpToSeconds(executionStartToCloseTimeout),
          roundUpToSeconds(
              taskStartToCloseTimeout, OptionsUtils.DEFAULT_TASK_START_TO_CLOSE_TIMEOUT),
          taskList,
          childPolicy,
          retryOptions,
          cronSchedule,
          memo,
          searchAttributes);
    }
  }

  private final String workflowId;

  private final WorkflowIdReusePolicy workflowIdReusePolicy;

  private final Duration executionStartToCloseTimeout;

  private final Duration taskStartToCloseTimeout;

  private final String taskList;

  private final ChildPolicy childPolicy;

  private RetryOptions retryOptions;

  private String cronSchedule;

  private Map<String, Object> memo;

  private Map<String, Object> searchAttributes;

  private WorkflowOptions(
      String workflowId,
      WorkflowIdReusePolicy workflowIdReusePolicy,
      Duration executionStartToCloseTimeout,
      Duration taskStartToCloseTimeout,
      String taskList,
      ChildPolicy childPolicy,
      RetryOptions retryOptions,
      String cronSchedule,
      Map<String, Object> memo,
      Map<String, Object> searchAttributes) {
    this.workflowId = workflowId;
    this.workflowIdReusePolicy = workflowIdReusePolicy;
    this.executionStartToCloseTimeout = executionStartToCloseTimeout;
    this.taskStartToCloseTimeout = taskStartToCloseTimeout;
    this.taskList = taskList;
    this.childPolicy = childPolicy;
    this.retryOptions = retryOptions;
    this.cronSchedule = cronSchedule;
    this.memo = memo;
    this.searchAttributes = searchAttributes;
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

  public RetryOptions getRetryOptions() {
    return retryOptions;
  }

  public String getCronSchedule() {
    return cronSchedule;
  }

  public Map<String, Object> getMemo() {
    return memo;
  }

  public Map<String, Object> getSearchAttributes() {
    return searchAttributes;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowOptions that = (WorkflowOptions) o;
    return Objects.equals(workflowId, that.workflowId)
        && workflowIdReusePolicy == that.workflowIdReusePolicy
        && Objects.equals(executionStartToCloseTimeout, that.executionStartToCloseTimeout)
        && Objects.equals(taskStartToCloseTimeout, that.taskStartToCloseTimeout)
        && Objects.equals(taskList, that.taskList)
        && childPolicy == that.childPolicy
        && Objects.equals(retryOptions, that.retryOptions)
        && Objects.equals(cronSchedule, that.cronSchedule)
        && Objects.equals(memo, that.memo)
        && Objects.equals(searchAttributes, that.searchAttributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        workflowId,
        workflowIdReusePolicy,
        executionStartToCloseTimeout,
        taskStartToCloseTimeout,
        taskList,
        childPolicy,
        retryOptions,
        cronSchedule,
        memo,
        searchAttributes);
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
        + ", retryOptions="
        + retryOptions
        + ", cronSchedule='"
        + cronSchedule
        + '\''
        + ", memo='"
        + memo
        + '\''
        + ", searchAttributes='"
        + searchAttributes
        + '\''
        + '}';
  }
}
