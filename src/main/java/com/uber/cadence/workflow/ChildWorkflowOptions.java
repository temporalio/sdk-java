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

import static com.uber.cadence.internal.common.OptionsUtils.roundUpToSeconds;

import com.uber.cadence.ParentClosePolicy;
import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.common.CronSchedule;
import com.uber.cadence.common.MethodRetry;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.context.ContextPropagator;
import com.uber.cadence.internal.common.OptionsUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ChildWorkflowOptions {

  public static ChildWorkflowOptions merge(
      WorkflowMethod a, MethodRetry r, CronSchedule cronSchedule, ChildWorkflowOptions o) {
    if (o == null) {
      o = new ChildWorkflowOptions.Builder().build();
    }

    String cronAnnotation = cronSchedule == null ? "" : cronSchedule.value();
    return new ChildWorkflowOptions.Builder()
        .setDomain(o.getDomain())
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
        .setRetryOptions(RetryOptions.merge(r, o.getRetryOptions()))
        .setCronSchedule(OptionsUtils.merge(cronAnnotation, o.getCronSchedule(), String.class))
        .setParentClosePolicy(o.getParentClosePolicy())
        .setMemo(o.getMemo())
        .setSearchAttributes(o.getSearchAttributes())
        .setContextPropagators(o.getContextPropagators())
        .validateAndBuildWithDefaults();
  }

  public static final class Builder {

    private String domain;

    private String workflowId;

    private WorkflowIdReusePolicy workflowIdReusePolicy;

    private Duration executionStartToCloseTimeout;

    private Duration taskStartToCloseTimeout;

    private String taskList;

    private RetryOptions retryOptions;

    private String cronSchedule;

    private ParentClosePolicy parentClosePolicy;

    private Map<String, Object> memo;

    private Map<String, Object> searchAttributes;

    private List<ContextPropagator> contextPropagators;

    public Builder() {}

    public Builder(ChildWorkflowOptions source) {
      if (source == null) {
        return;
      }
      this.domain = source.getDomain();
      this.workflowId = source.getWorkflowId();
      this.workflowIdReusePolicy = source.getWorkflowIdReusePolicy();
      this.executionStartToCloseTimeout = source.getExecutionStartToCloseTimeout();
      this.taskStartToCloseTimeout = source.getTaskStartToCloseTimeout();
      this.taskList = source.getTaskList();
      this.retryOptions = source.getRetryOptions();
      this.cronSchedule = source.getCronSchedule();
      this.parentClosePolicy = source.getParentClosePolicy();
      this.memo = source.getMemo();
      this.searchAttributes = source.getSearchAttributes();
      this.contextPropagators = source.getContextPropagators();
    }

    /**
     * Specify domain in which workflow should be started.
     *
     * <p>TODO: Resolve conflict with WorkflowClient domain.
     */
    public Builder setDomain(String domain) {
      this.domain = domain;
      return this;
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

    /**
     * RetryOptions that define how child workflow is retried in case of failure. Default is null
     * which is no reties.
     */
    public Builder setRetryOptions(RetryOptions retryOptions) {
      this.retryOptions = retryOptions;
      return this;
    }

    public Builder setCronSchedule(String cronSchedule) {
      this.cronSchedule = cronSchedule;
      return this;
    }

    /** Specifies how this workflow reacts to the death of the parent workflow. */
    public Builder setParentClosePolicy(ParentClosePolicy parentClosePolicy) {
      this.parentClosePolicy = parentClosePolicy;
      return this;
    }

    /** Specifies additional non-indexed information in result of list workflow. */
    public Builder setMemo(Map<String, Object> memo) {
      this.memo = memo;
      return this;
    }

    /** Specifies additional indexed information in result of list workflow. */
    public Builder setSearchAttributes(Map<String, Object> searchAttributes) {
      this.searchAttributes = searchAttributes;
      return this;
    }

    /** Specifies the list of context propagators to use during this workflow. */
    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    public ChildWorkflowOptions build() {
      return new ChildWorkflowOptions(
          domain,
          workflowId,
          workflowIdReusePolicy,
          executionStartToCloseTimeout,
          taskStartToCloseTimeout,
          taskList,
          retryOptions,
          cronSchedule,
          parentClosePolicy,
          memo,
          searchAttributes,
          contextPropagators);
    }

    public ChildWorkflowOptions validateAndBuildWithDefaults() {
      return new ChildWorkflowOptions(
          domain,
          workflowId,
          workflowIdReusePolicy,
          roundUpToSeconds(executionStartToCloseTimeout),
          roundUpToSeconds(taskStartToCloseTimeout),
          taskList,
          retryOptions,
          cronSchedule,
          parentClosePolicy,
          memo,
          searchAttributes,
          contextPropagators);
    }
  }

  private final String domain;

  private final String workflowId;

  private final WorkflowIdReusePolicy workflowIdReusePolicy;

  private final Duration executionStartToCloseTimeout;

  private final Duration taskStartToCloseTimeout;

  private final String taskList;

  private final RetryOptions retryOptions;

  private final String cronSchedule;

  private final ParentClosePolicy parentClosePolicy;

  private final Map<String, Object> memo;

  private final Map<String, Object> searchAttributes;

  private List<ContextPropagator> contextPropagators;

  private ChildWorkflowOptions(
      String domain,
      String workflowId,
      WorkflowIdReusePolicy workflowIdReusePolicy,
      Duration executionStartToCloseTimeout,
      Duration taskStartToCloseTimeout,
      String taskList,
      RetryOptions retryOptions,
      String cronSchedule,
      ParentClosePolicy parentClosePolicy,
      Map<String, Object> memo,
      Map<String, Object> searchAttributes,
      List<ContextPropagator> contextPropagators) {
    this.domain = domain;
    this.workflowId = workflowId;
    this.workflowIdReusePolicy = workflowIdReusePolicy;
    this.executionStartToCloseTimeout = executionStartToCloseTimeout;
    this.taskStartToCloseTimeout = taskStartToCloseTimeout;
    this.taskList = taskList;
    this.retryOptions = retryOptions;
    this.cronSchedule = cronSchedule;
    this.parentClosePolicy = parentClosePolicy;
    this.memo = memo;
    this.searchAttributes = searchAttributes;
    this.contextPropagators = contextPropagators;
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

  public Duration getExecutionStartToCloseTimeout() {
    return executionStartToCloseTimeout;
  }

  public Duration getTaskStartToCloseTimeout() {
    return taskStartToCloseTimeout;
  }

  public String getTaskList() {
    return taskList;
  }

  public RetryOptions getRetryOptions() {
    return retryOptions;
  }

  public String getCronSchedule() {
    return cronSchedule;
  }

  public ParentClosePolicy getParentClosePolicy() {
    return parentClosePolicy;
  }

  public Map<String, Object> getMemo() {
    return memo;
  }

  public Map<String, Object> getSearchAttributes() {
    return searchAttributes;
  }

  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ChildWorkflowOptions that = (ChildWorkflowOptions) o;
    return Objects.equals(domain, that.domain)
        && Objects.equals(workflowId, that.workflowId)
        && workflowIdReusePolicy == that.workflowIdReusePolicy
        && Objects.equals(executionStartToCloseTimeout, that.executionStartToCloseTimeout)
        && Objects.equals(taskStartToCloseTimeout, that.taskStartToCloseTimeout)
        && Objects.equals(taskList, that.taskList)
        && Objects.equals(retryOptions, that.retryOptions)
        && Objects.equals(cronSchedule, that.cronSchedule)
        && Objects.equals(parentClosePolicy, that.parentClosePolicy)
        && Objects.equals(memo, that.memo)
        && Objects.equals(searchAttributes, that.searchAttributes)
        && Objects.equals(contextPropagators, that.contextPropagators);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        domain,
        workflowId,
        workflowIdReusePolicy,
        executionStartToCloseTimeout,
        taskStartToCloseTimeout,
        taskList,
        retryOptions,
        cronSchedule,
        parentClosePolicy,
        memo,
        searchAttributes,
        contextPropagators);
  }

  @Override
  public String toString() {
    return "ChildWorkflowOptions{"
        + "domain='"
        + domain
        + '\''
        + ", workflowId='"
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
        + ", retryOptions="
        + retryOptions
        + ", cronSchedule="
        + cronSchedule
        + ", parentClosePolicy="
        + parentClosePolicy
        + ", memo='"
        + memo
        + '\''
        + ", searchAttributes='"
        + searchAttributes
        + ", contextPropagators='"
        + contextPropagators
        + '\''
        + '}';
  }
}
