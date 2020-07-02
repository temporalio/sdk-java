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

package io.temporal.workflow;

import static io.temporal.internal.common.OptionsUtils.roundUpToSeconds;

import com.google.common.base.Objects;
import io.temporal.common.CronSchedule;
import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.enums.v1.ParentClosePolicy;
import io.temporal.enums.v1.WorkflowIdReusePolicy;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.OptionsUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class ChildWorkflowOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ChildWorkflowOptions options) {
    return new Builder(options);
  }

  public static ChildWorkflowOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final ChildWorkflowOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ChildWorkflowOptions.newBuilder().build();
  }

  public static final class Builder {

    private String namespace;

    private String workflowId;

    private WorkflowIdReusePolicy workflowIdReusePolicy;

    private Duration workflowRunTimeout;

    private Duration workflowExecutionTimeout;

    private Duration workflowTaskTimeout;

    private String taskQueue;

    private RetryOptions retryOptions;

    private String cronSchedule;

    private ParentClosePolicy parentClosePolicy;

    private Map<String, Object> memo;

    private Map<String, Object> searchAttributes;

    private List<ContextPropagator> contextPropagators;

    private ChildWorkflowCancellationType cancellationType;

    private Builder() {}

    private Builder(ChildWorkflowOptions options) {
      if (options == null) {
        return;
      }
      this.namespace = options.getNamespace();
      this.workflowId = options.getWorkflowId();
      this.workflowIdReusePolicy = options.getWorkflowIdReusePolicy();
      this.workflowRunTimeout = options.getWorkflowRunTimeout();
      this.workflowExecutionTimeout = options.getWorkflowExecutionTimeout();
      this.workflowTaskTimeout = options.getWorkflowTaskTimeout();
      this.taskQueue = options.getTaskQueue();
      this.retryOptions = options.getRetryOptions();
      this.cronSchedule = options.getCronSchedule();
      this.parentClosePolicy = options.getParentClosePolicy();
      this.memo = options.getMemo();
      this.searchAttributes = options.getSearchAttributes();
      this.contextPropagators = options.getContextPropagators();
      this.cancellationType = options.getCancellationType();
    }

    /**
     * Specify namespace in which workflow should be started.
     *
     * <p>TODO: Resolve conflict with WorkflowClient namespace.
     */
    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
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
     * conditions Temporal allows two workflows with the same namespace and workflow id run
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
     * The time after which workflow run is automatically terminated by the Temporal service. Do not
     * rely on the run timeout for business level timeouts. It is preferred to use in workflow
     * timers for this purpose.
     */
    public Builder setWorkflowRunTimeout(Duration workflowRunTimeout) {
      this.workflowRunTimeout = workflowRunTimeout;
      return this;
    }

    /**
     * The maximum time that parent workflow is willing to wait for a child execution (which
     * includes retries and continue as new calls). If exceeded the child is automatically
     * terminated by the Temporal service.
     */
    public Builder setWorkflowExecutionTimeout(Duration workflowExecutionTimeout) {
      this.workflowExecutionTimeout = workflowExecutionTimeout;
      return this;
    }

    /**
     * Maximum execution time of a single decision task. Default is 10 seconds. Maximum accepted
     * value is 60 seconds.
     */
    public Builder setWorkflowTaskTimeout(Duration workflowTaskTimeout) {
      if (roundUpToSeconds(workflowTaskTimeout) > 60) {
        throw new IllegalArgumentException(
            "WorkflowTaskTimeout over one minute: " + workflowTaskTimeout);
      }
      this.workflowTaskTimeout = workflowTaskTimeout;
      return this;
    }

    /**
     * Task queue to use for decision tasks. It should match a task queue specified when creating a
     * {@link io.temporal.worker.Worker} that hosts the workflow code.
     */
    public Builder setTaskQueue(String taskQueue) {
      this.taskQueue = taskQueue;
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

    /**
     * In case of a child workflow cancellation it fails with a {@link CanceledFailure}. The type
     * defines at which point the exception is thrown.
     */
    public Builder setCancellationType(ChildWorkflowCancellationType cancellationType) {
      this.cancellationType = cancellationType;
      return this;
    }

    public Builder setMethodRetry(MethodRetry r) {
      retryOptions = RetryOptions.merge(r, retryOptions);
      return this;
    }

    public Builder setCronSchedule(CronSchedule c) {
      String cronAnnotation = c == null ? "" : c.value();
      cronSchedule = OptionsUtils.merge(cronAnnotation, cronSchedule, String.class);
      return this;
    }

    public ChildWorkflowOptions build() {
      return new ChildWorkflowOptions(
          namespace,
          workflowId,
          workflowIdReusePolicy,
          workflowRunTimeout,
          workflowExecutionTimeout,
          workflowTaskTimeout,
          taskQueue,
          retryOptions,
          cronSchedule,
          parentClosePolicy,
          memo,
          searchAttributes,
          contextPropagators,
          cancellationType);
    }

    public ChildWorkflowOptions validateAndBuildWithDefaults() {
      return new ChildWorkflowOptions(
          namespace,
          workflowId,
          workflowIdReusePolicy,
          workflowRunTimeout,
          workflowExecutionTimeout,
          workflowTaskTimeout,
          taskQueue,
          retryOptions,
          cronSchedule,
          parentClosePolicy,
          memo,
          searchAttributes,
          contextPropagators,
          cancellationType == null
              ? ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED
              : cancellationType);
    }
  }

  private final String namespace;

  private final String workflowId;

  private final WorkflowIdReusePolicy workflowIdReusePolicy;

  private final Duration workflowRunTimeout;

  private final Duration workflowExecutionTimeout;

  private final Duration workflowTaskTimeout;

  private final String taskQueue;

  private final RetryOptions retryOptions;

  private final String cronSchedule;

  private final ParentClosePolicy parentClosePolicy;

  private final Map<String, Object> memo;

  private final Map<String, Object> searchAttributes;

  private List<ContextPropagator> contextPropagators;

  private final ChildWorkflowCancellationType cancellationType;

  private ChildWorkflowOptions(
      String namespace,
      String workflowId,
      WorkflowIdReusePolicy workflowIdReusePolicy,
      Duration workflowRunTimeout,
      Duration workflowExecutionTimeout,
      Duration workflowTaskTimeout,
      String taskQueue,
      RetryOptions retryOptions,
      String cronSchedule,
      ParentClosePolicy parentClosePolicy,
      Map<String, Object> memo,
      Map<String, Object> searchAttributes,
      List<ContextPropagator> contextPropagators,
      ChildWorkflowCancellationType cancellationType) {
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.workflowIdReusePolicy = workflowIdReusePolicy;
    this.workflowRunTimeout = workflowRunTimeout;
    this.workflowExecutionTimeout = workflowExecutionTimeout;
    this.workflowTaskTimeout = workflowTaskTimeout;
    this.taskQueue = taskQueue;
    this.retryOptions = retryOptions;
    this.cronSchedule = cronSchedule;
    this.parentClosePolicy = parentClosePolicy;
    this.memo = memo;
    this.searchAttributes = searchAttributes;
    this.contextPropagators = contextPropagators;
    this.cancellationType = cancellationType;
  }

  public String getNamespace() {
    return namespace;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public WorkflowIdReusePolicy getWorkflowIdReusePolicy() {
    return workflowIdReusePolicy;
  }

  public Duration getWorkflowRunTimeout() {
    return workflowRunTimeout;
  }

  public Duration getWorkflowExecutionTimeout() {
    return workflowExecutionTimeout;
  }

  public Duration getWorkflowTaskTimeout() {
    return workflowTaskTimeout;
  }

  public String getTaskQueue() {
    return taskQueue;
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

  public ChildWorkflowCancellationType getCancellationType() {
    return cancellationType;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ChildWorkflowOptions that = (ChildWorkflowOptions) o;
    return Objects.equal(namespace, that.namespace)
        && Objects.equal(workflowId, that.workflowId)
        && workflowIdReusePolicy == that.workflowIdReusePolicy
        && Objects.equal(workflowRunTimeout, that.workflowRunTimeout)
        && Objects.equal(workflowExecutionTimeout, that.workflowExecutionTimeout)
        && Objects.equal(workflowTaskTimeout, that.workflowTaskTimeout)
        && Objects.equal(taskQueue, that.taskQueue)
        && Objects.equal(retryOptions, that.retryOptions)
        && Objects.equal(cronSchedule, that.cronSchedule)
        && parentClosePolicy == that.parentClosePolicy
        && Objects.equal(memo, that.memo)
        && Objects.equal(searchAttributes, that.searchAttributes)
        && Objects.equal(contextPropagators, that.contextPropagators)
        && cancellationType == that.cancellationType;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        namespace,
        workflowId,
        workflowIdReusePolicy,
        workflowRunTimeout,
        workflowExecutionTimeout,
        workflowTaskTimeout,
        taskQueue,
        retryOptions,
        cronSchedule,
        parentClosePolicy,
        memo,
        searchAttributes,
        contextPropagators,
        cancellationType);
  }

  @Override
  public String toString() {
    return "ChildWorkflowOptions{"
        + "namespace='"
        + namespace
        + '\''
        + ", workflowId='"
        + workflowId
        + '\''
        + ", workflowIdReusePolicy="
        + workflowIdReusePolicy
        + ", workflowRunTimeout="
        + workflowRunTimeout
        + ", workflowExecutionTimeout="
        + workflowExecutionTimeout
        + ", workflowTaskTimeout="
        + workflowTaskTimeout
        + ", taskQueue='"
        + taskQueue
        + '\''
        + ", retryOptions="
        + retryOptions
        + ", cronSchedule='"
        + cronSchedule
        + '\''
        + ", parentClosePolicy="
        + parentClosePolicy
        + ", memo="
        + memo
        + ", searchAttributes="
        + searchAttributes
        + ", contextPropagators="
        + contextPropagators
        + ", cancellationType="
        + cancellationType
        + '}';
  }
}
