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

import static io.temporal.internal.common.OptionsUtils.roundUpToSeconds;

import com.google.common.base.Objects;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.common.*;
import io.temporal.common.context.ContextPropagator;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.failure.TimeoutFailure;
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
    private SearchAttributes typedSearchAttributes;
    private List<ContextPropagator> contextPropagators;
    private ChildWorkflowCancellationType cancellationType;
    private VersioningIntent versioningIntent;

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
      this.typedSearchAttributes = options.getTypedSearchAttributes();
      this.contextPropagators = options.getContextPropagators();
      this.cancellationType = options.getCancellationType();
      this.versioningIntent = options.getVersioningIntent();
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
     *       previous run failed or was canceled or terminated.
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
     * The time after which child workflow run is automatically terminated by Temporal service with
     * CHILD_WORKFLOW_EXECUTION_TIMED_OUT status. <br>
     * Parent workflow receives {@link ChildWorkflowFailure} exception with {@link TimeoutFailure}
     * cause from the child's {@link Promise} if this happens.
     *
     * <p>When a workflow reaches Workflow Run Timeout, it can't make any progress after that. Do
     * not rely on this timeout in workflow implementation or business logic. This timeout is not
     * designed to be handled in workflow code to perform any logic in case of timeout. Consider
     * using workflow timers instead.
     *
     * <p>If you catch yourself setting this timeout to very small values, you're likely using it
     * wrong.
     *
     * <p>Example: If Workflow Run Timeout is 30 seconds and the network was unavailable for 1
     * minute, workflows that were scheduled before the network blip will never have a chance to
     * make progress or react, and will be terminated. <br>
     * A timer that is scheduled in the workflow code using {@link Workflow#newTimer(Duration)} will
     * handle this situation gracefully. A workflow with such a timer will start after the network
     * blip. If it started before the network blip and the timer fires during the network blip, it
     * will get delivered after connectivity is restored and the workflow will be able to resume.
     */
    public Builder setWorkflowRunTimeout(Duration workflowRunTimeout) {
      this.workflowRunTimeout = workflowRunTimeout;
      return this;
    }

    /**
     * The time after which child workflow execution (which includes run retries and continue as
     * new) is automatically terminated by Temporal service with WORKFLOW_EXECUTION_TIMED_OUT
     * status. <br>
     * Parent workflow receives {@link ChildWorkflowFailure} exception with {@link TimeoutFailure}
     * cause from the child's {@link Promise} if this happens.
     *
     * <p>When a workflow reaches Workflow Execution Timeout, it can't make any progress after that.
     * Do not rely on this timeout in workflow implementation or business logic. This timeout is not
     * designed to be handled in workflow code to perform any logic in case of timeout. Consider
     * using workflow timers instead.
     *
     * <p>If you catch yourself setting this timeout to very small values, you're likely using it
     * wrong.
     *
     * <p>Example: If Workflow Execution Timeout is 30 seconds and the network was unavailable for 1
     * minute, workflows that were scheduled before the network blip will never have a chance to
     * make progress or react, and will be terminated. <br>
     * A timer that is scheduled in the workflow code using {@link Workflow#newTimer(Duration)} will
     * handle this situation gracefully. A workflow with such a timer will start after the network
     * blip. If it started before the network blip and the timer fires during the network blip, it
     * will get delivered after connectivity is restored and the workflow will be able to resume.
     */
    public Builder setWorkflowExecutionTimeout(Duration workflowExecutionTimeout) {
      this.workflowExecutionTimeout = workflowExecutionTimeout;
      return this;
    }

    /**
     * Maximum execution time of a single workflow task. Default is 10 seconds. Maximum accepted
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
     * Task queue to use for workflow tasks. It should match a task queue specified when creating a
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

    /**
     * Specifies additional indexed information in result of list workflow.
     *
     * @deprecated use {@link #setTypedSearchAttributes} instead.
     */
    @Deprecated
    public Builder setSearchAttributes(Map<String, Object> searchAttributes) {
      if (searchAttributes != null
          && !searchAttributes.isEmpty()
          && this.typedSearchAttributes != null) {
        throw new IllegalArgumentException(
            "Cannot have search attributes and typed search attributes");
      }
      this.searchAttributes = searchAttributes;
      return this;
    }

    /** Specifies additional indexed information in result of list workflow. */
    public Builder setTypedSearchAttributes(SearchAttributes typedSearchAttributes) {
      if (typedSearchAttributes != null
          && searchAttributes != null
          && !searchAttributes.isEmpty()) {
        throw new IllegalArgumentException(
            "Cannot have typed search attributes and search attributes");
      }
      this.typedSearchAttributes = typedSearchAttributes;
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

    /**
     * Specifies whether this child workflow should run on a worker with a compatible Build Id or
     * not. See the variants of {@link VersioningIntent}.
     */
    public Builder setVersioningIntent(VersioningIntent versioningIntent) {
      this.versioningIntent = versioningIntent;
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
          typedSearchAttributes,
          contextPropagators,
          cancellationType,
          versioningIntent);
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
          typedSearchAttributes,
          contextPropagators,
          cancellationType == null
              ? ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED
              : cancellationType,
          versioningIntent == null
              ? VersioningIntent.VERSIONING_INTENT_UNSPECIFIED
              : versioningIntent);
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
  private final SearchAttributes typedSearchAttributes;
  private final List<ContextPropagator> contextPropagators;
  private final ChildWorkflowCancellationType cancellationType;
  private final VersioningIntent versioningIntent;

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
      SearchAttributes typedSearchAttributes,
      List<ContextPropagator> contextPropagators,
      ChildWorkflowCancellationType cancellationType,
      VersioningIntent versioningIntent) {
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
    this.typedSearchAttributes = typedSearchAttributes;
    this.contextPropagators = contextPropagators;
    this.cancellationType = cancellationType;
    this.versioningIntent = versioningIntent;
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

  /**
   * @deprecated use {@link #getTypedSearchAttributes} instead.
   */
  @Deprecated
  public Map<String, Object> getSearchAttributes() {
    return searchAttributes;
  }

  public SearchAttributes getTypedSearchAttributes() {
    return typedSearchAttributes;
  }

  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  public ChildWorkflowCancellationType getCancellationType() {
    return cancellationType;
  }

  public VersioningIntent getVersioningIntent() {
    return versioningIntent;
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
        && Objects.equal(typedSearchAttributes, that.typedSearchAttributes)
        && Objects.equal(contextPropagators, that.contextPropagators)
        && cancellationType == that.cancellationType
        && versioningIntent == that.versioningIntent;
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
        typedSearchAttributes,
        contextPropagators,
        cancellationType,
        versioningIntent);
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
        + ", typedSearchAttributes="
        + typedSearchAttributes
        + ", contextPropagators="
        + contextPropagators
        + ", cancellationType="
        + cancellationType
        + ", versioningIntent="
        + versioningIntent
        + '}';
  }
}
