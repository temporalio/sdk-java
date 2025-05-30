package io.temporal.client;

import com.google.common.base.Objects;
import io.temporal.api.common.v1.Callback;
import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.common.*;
import io.temporal.common.context.ContextPropagator;
import io.temporal.internal.common.OptionsUtils;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public final class WorkflowOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(WorkflowOptions options) {
    return new Builder(options);
  }

  public static WorkflowOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final WorkflowOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = WorkflowOptions.newBuilder().build();
  }

  public static WorkflowOptions merge(
      MethodRetry methodRetry, CronSchedule cronSchedule, WorkflowOptions o) {
    if (o == null) {
      o = WorkflowOptions.newBuilder().build();
    }
    String cronAnnotation = cronSchedule == null ? "" : cronSchedule.value();
    return WorkflowOptions.newBuilder()
        .setWorkflowId(o.getWorkflowId())
        .setWorkflowIdReusePolicy(o.getWorkflowIdReusePolicy())
        .setWorkflowRunTimeout(o.getWorkflowRunTimeout())
        .setWorkflowExecutionTimeout(o.getWorkflowExecutionTimeout())
        .setWorkflowTaskTimeout(o.getWorkflowTaskTimeout())
        .setTaskQueue(o.getTaskQueue())
        .setRetryOptions(RetryOptions.merge(methodRetry, o.getRetryOptions()))
        .setCronSchedule(OptionsUtils.merge(cronAnnotation, o.getCronSchedule(), String.class))
        .setMemo(o.getMemo())
        .setSearchAttributes(o.getSearchAttributes())
        .setTypedSearchAttributes(o.getTypedSearchAttributes())
        .setContextPropagators(o.getContextPropagators())
        .setDisableEagerExecution(o.isDisableEagerExecution())
        .setStartDelay(o.getStartDelay())
        .setWorkflowIdConflictPolicy(o.getWorkflowIdConflictPolicy())
        .setStaticSummary(o.getStaticSummary())
        .setStaticDetails(o.getStaticDetails())
        .setRequestId(o.getRequestId())
        .setCompletionCallbacks(o.getCompletionCallbacks())
        .setLinks(o.getLinks())
        .setOnConflictOptions(o.getOnConflictOptions())
        .setPriority(o.getPriority())
        .setVersioningOverride(o.getVersioningOverride())
        .validateBuildWithDefaults();
  }

  public static final class Builder {

    private String workflowId;

    private WorkflowIdReusePolicy workflowIdReusePolicy;

    private Duration workflowRunTimeout;

    private Duration workflowExecutionTimeout;

    private Duration workflowTaskTimeout;

    private String taskQueue;

    private RetryOptions retryOptions;

    private String cronSchedule;

    private Map<String, Object> memo;

    private Map<String, ?> searchAttributes;

    private SearchAttributes typedSearchAttributes;

    private List<ContextPropagator> contextPropagators;

    private boolean disableEagerExecution = true;

    private Duration startDelay;

    private WorkflowIdConflictPolicy workflowIdConflictPolicy;

    private String staticSummary;

    private String staticDetails;

    private String requestId;

    private List<Callback> completionCallbacks;

    private List<Link> links;

    private OnConflictOptions onConflictOptions;

    private Priority priority;

    private VersioningOverride versioningOverride;

    private Builder() {}

    private Builder(WorkflowOptions options) {
      if (options == null) {
        return;
      }
      this.workflowIdReusePolicy = options.workflowIdReusePolicy;
      this.workflowId = options.workflowId;
      this.workflowTaskTimeout = options.workflowTaskTimeout;
      this.workflowRunTimeout = options.workflowRunTimeout;
      this.workflowExecutionTimeout = options.workflowExecutionTimeout;
      this.taskQueue = options.taskQueue;
      this.retryOptions = options.retryOptions;
      this.cronSchedule = options.cronSchedule;
      this.memo = options.memo;
      this.searchAttributes = options.searchAttributes;
      this.typedSearchAttributes = options.typedSearchAttributes;
      this.contextPropagators = options.contextPropagators;
      this.disableEagerExecution = options.disableEagerExecution;
      this.startDelay = options.startDelay;
      this.workflowIdConflictPolicy = options.workflowIdConflictPolicy;
      this.staticSummary = options.staticSummary;
      this.staticDetails = options.staticDetails;
      this.requestId = options.requestId;
      this.completionCallbacks = options.completionCallbacks;
      this.links = options.links;
      this.onConflictOptions = options.onConflictOptions;
      this.priority = options.priority;
      this.versioningOverride = options.versioningOverride;
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
     * simultaneously. See {@line setWorkflowIdConflictPolicy} for handling a workflow id
     * duplication with a <b>Running</b> workflow.
     *
     * <p>Default value if not set: <b>AllowDuplicate</b>
     *
     * <ul>
     *   <li><b>AllowDuplicate</b> allows a new run regardless of the previous run's final status.
     *       The previous run still must be closed or the new run will be rejected.
     *   <li><b>AllowDuplicateFailedOnly</b> allows a new run if the previous run failed, was
     *       canceled, or terminated.
     *   <li><b>RejectDuplicate</b> never allows a new run, regardless of the previous run's final
     *       status.
     *   <li><b>TerminateIfRunning</b> is the same as <b>AllowDuplicate</b>, but if there exists a
     *       not-closed run in progress, it will be terminated.
     * </ul>
     */
    public Builder setWorkflowIdReusePolicy(WorkflowIdReusePolicy workflowIdReusePolicy) {
      this.workflowIdReusePolicy = workflowIdReusePolicy;
      return this;
    }

    /**
     * Specifies server behavior if a <b>Running</b> workflow with the same id exists. See {@link
     * #setWorkflowIdReusePolicy} for handling a workflow id duplication with a <b>Closed</b>
     * workflow. Cannot be set when {@link #getWorkflowIdReusePolicy()} is {@link
     * WorkflowIdReusePolicy#WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING}.
     *
     * <ul>
     *   <li><b>Fail</b> Don't start a new workflow; instead return {@link
     *       WorkflowExecutionAlreadyStarted}
     *   <li><b>UseExisting</b> Don't start a new workflow; instead return the handle for the
     *       running workflow.
     *   <li><b>TerminateExisting</b> Terminate the running workflow before starting a new one.
     * </ul>
     */
    public Builder setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy workflowIdConflictPolicy) {
      this.workflowIdConflictPolicy = workflowIdConflictPolicy;
      return this;
    }

    /**
     * The time after which a workflow run is automatically terminated by Temporal service with
     * WORKFLOW_EXECUTION_TIMED_OUT status.
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
     * The time after which workflow execution (which includes run retries and continue as new) is
     * automatically terminated by Temporal service with WORKFLOW_EXECUTION_TIMED_OUT status.
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
     * Maximum execution time of a single Workflow Task. In the majority of cases there is no need
     * to change this timeout. Note that this timeout is not related to the overall Workflow
     * duration in any way. It defines for how long the Workflow can get blocked in the case of a
     * Workflow Worker crash.
     *
     * <p>Default is 10 seconds. Maximum value allowed by the Temporal Server is 120 seconds.
     */
    public Builder setWorkflowTaskTimeout(Duration workflowTaskTimeout) {
      this.workflowTaskTimeout = workflowTaskTimeout;
      return this;
    }

    /**
     * Task queue to use for workflow tasks. It should match a task queue specified when creating a
     * {@link Worker} that hosts the workflow code.
     */
    public Builder setTaskQueue(String taskQueue) {
      this.taskQueue = taskQueue;
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
     * can be any object that are serializable by {@link io.temporal.common.converter.DataConverter}
     */
    public Builder setMemo(Map<String, Object> memo) {
      this.memo = memo;
      return this;
    }

    /**
     * Specifies Search Attributes map {@code searchAttributes} that will be attached to the
     * Workflow. Search Attributes are additional indexed information attributed to workflow and
     * used for search and visibility.
     *
     * <p>The search attributes can be used in query of List/Scan/Count workflow APIs. The key and
     * its value type must be registered on Temporal server side.
     *
     * <p>Supported Java types of the value:
     *
     * <ul>
     *   <li>String
     *   <li>Long, Integer, Short, Byte
     *   <li>Boolean
     *   <li>Double
     *   <li>OffsetDateTime
     *   <li>{@link Collection} of the types above
     * </ul>
     *
     * @deprecated use {@link #setTypedSearchAttributes} instead.
     */
    // Workflow#upsertSearchAttributes docs needs to be kept in sync with this method
    @Deprecated
    public Builder setSearchAttributes(Map<String, ?> searchAttributes) {
      if (searchAttributes != null
          && !searchAttributes.isEmpty()
          && this.typedSearchAttributes != null) {
        throw new IllegalArgumentException(
            "Cannot have search attributes and typed search attributes");
      }
      this.searchAttributes = searchAttributes;
      return this;
    }

    /**
     * Specifies Search Attributes that will be attached to the Workflow. Search Attributes are
     * additional indexed information attributed to workflow and used for search and visibility.
     *
     * <p>The search attributes can be used in query of List/Scan/Count workflow APIs. The key and
     * its value type must be registered on Temporal server side.
     */
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

    /**
     * This list of context propagators overrides the list specified on {@link
     * WorkflowClientOptions#getContextPropagators()}. <br>
     * This method is uncommon, the majority of users should just set {@link
     * WorkflowClientOptions#getContextPropagators()}
     *
     * @param contextPropagators specifies the list of overriding context propagators, {@code null}
     *     means no overriding.
     */
    public Builder setContextPropagators(@Nullable List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    /**
     * If {@link WorkflowClient} is used to create a {@link WorkerFactory} that is
     *
     * <ul>
     *   <li>started
     *   <li>has a non-paused worker on the right task queue
     *   <li>has available workflow task executor slots
     * </ul>
     *
     * <p>and such a {@link WorkflowClient} is used to start a workflow, then the first workflow
     * task could be dispatched on this local worker with the response to the start call if Server
     * supports it. This option can be used to disable this mechanism.
     *
     * <p>Default is true
     *
     * <p>WARNING: Eager start does not respect worker versioning. An eagerly started workflow may
     * run on any available local worker even if that worker is not in the default build ID set.
     *
     * @param disableEagerExecution if true, an eager local execution of the workflow task will
     *     never be requested even if it is possible.
     */
    public Builder setDisableEagerExecution(boolean disableEagerExecution) {
      this.disableEagerExecution = disableEagerExecution;
      return this;
    }

    /**
     * Time to wait before dispatching the first workflow task. A signal from signal with start will
     * not trigger a workflow task. Cannot be set the same time as a CronSchedule.
     */
    public Builder setStartDelay(Duration startDelay) {
      this.startDelay = startDelay;
      return this;
    }

    /**
     * Single-line fixed summary for this workflow execution that will appear in UI/CLI. This can be
     * in single-line Temporal Markdown format.
     *
     * <p>Default is none/empty.
     */
    @Experimental
    public Builder setStaticSummary(String staticSummary) {
      this.staticSummary = staticSummary;
      return this;
    }

    /**
     * General fixed details for this workflow execution that will appear in UI/CLI. This can be in
     * Temporal Markdown format and can span multiple lines. This is a fixed value on the workflow
     * that cannot be updated.
     *
     * <p>Default is none/empty.
     */
    @Experimental
    public Builder setStaticDetails(String staticDetails) {
      this.staticDetails = staticDetails;
      return this;
    }

    /**
     * A unique identifier for this start request.
     *
     * <p>WARNING: Not intended for User Code.
     */
    @Experimental
    public Builder setRequestId(String requestId) {
      this.requestId = requestId;
      return this;
    }

    /**
     * Callbacks to be called by the server when this workflow reaches a terminal state.
     *
     * <p>WARNING: Not intended for User Code.
     */
    @Experimental
    public Builder setCompletionCallbacks(List<Callback> completionCallbacks) {
      this.completionCallbacks = completionCallbacks;
      return this;
    }

    /**
     * Links to be associated with the workflow.
     *
     * <p>WARNING: Not intended for User Code.
     */
    @Experimental
    public Builder setLinks(List<Link> links) {
      this.links = links;
      return this;
    }

    /**
     * Set workflow ID conflict options used in conjunction with conflict policy
     * WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING. If onConflictOptions is set and a workflow is
     * already running, the options specifies the actions to be taken on the running workflow. If
     * not set or use together with any other WorkflowIDConflictPolicy, this parameter is ignored.
     *
     * <p>WARNING: Not intended for User Code.
     */
    @Experimental
    public Builder setOnConflictOptions(OnConflictOptions onConflictOptions) {
      this.onConflictOptions = onConflictOptions;
      return this;
    }

    /**
     * Optional priority settings that control relative ordering of task processing when tasks are
     * backed up in a queue.
     */
    @Experimental
    public Builder setPriority(Priority priority) {
      this.priority = priority;
      return this;
    }

    /** Sets the versioning override to use when starting this workflow. */
    @Experimental
    public Builder setVersioningOverride(VersioningOverride versioningOverride) {
      this.versioningOverride = versioningOverride;
      return this;
    }

    public WorkflowOptions build() {
      return new WorkflowOptions(
          workflowId,
          workflowIdReusePolicy,
          workflowRunTimeout,
          workflowExecutionTimeout,
          workflowTaskTimeout,
          taskQueue,
          retryOptions,
          cronSchedule,
          memo,
          searchAttributes,
          typedSearchAttributes,
          contextPropagators,
          disableEagerExecution,
          startDelay,
          workflowIdConflictPolicy,
          staticSummary,
          staticDetails,
          requestId,
          completionCallbacks,
          links,
          onConflictOptions,
          priority,
          versioningOverride);
    }

    /**
     * Validates that all required properties are set and fills all other with default parameters.
     */
    public WorkflowOptions validateBuildWithDefaults() {
      return new WorkflowOptions(
          workflowId,
          workflowIdReusePolicy,
          workflowRunTimeout,
          workflowExecutionTimeout,
          workflowTaskTimeout,
          taskQueue,
          retryOptions,
          cronSchedule,
          memo,
          searchAttributes,
          typedSearchAttributes,
          contextPropagators,
          disableEagerExecution,
          startDelay,
          workflowIdConflictPolicy,
          staticSummary,
          staticDetails,
          requestId,
          completionCallbacks,
          links,
          onConflictOptions,
          priority,
          versioningOverride);
    }
  }

  private final String workflowId;

  private final WorkflowIdReusePolicy workflowIdReusePolicy;

  private final Duration workflowRunTimeout;

  private final Duration workflowExecutionTimeout;

  private final Duration workflowTaskTimeout;

  private final String taskQueue;

  private final RetryOptions retryOptions;

  private final String cronSchedule;

  private final Map<String, Object> memo;

  private final Map<String, ?> searchAttributes;

  private final SearchAttributes typedSearchAttributes;

  private final List<ContextPropagator> contextPropagators;

  private final boolean disableEagerExecution;

  private final Duration startDelay;

  private final WorkflowIdConflictPolicy workflowIdConflictPolicy;

  private final String staticSummary;

  private final String staticDetails;

  private final String requestId;

  private final List<Callback> completionCallbacks;

  private final List<Link> links;
  private final OnConflictOptions onConflictOptions;
  private final Priority priority;
  private final VersioningOverride versioningOverride;

  private WorkflowOptions(
      String workflowId,
      WorkflowIdReusePolicy workflowIdReusePolicy,
      Duration workflowRunTimeout,
      Duration workflowExecutionTimeout,
      Duration workflowTaskTimeout,
      String taskQueue,
      RetryOptions retryOptions,
      String cronSchedule,
      Map<String, Object> memo,
      Map<String, ?> searchAttributes,
      SearchAttributes typedSearchAttributes,
      List<ContextPropagator> contextPropagators,
      boolean disableEagerExecution,
      Duration startDelay,
      WorkflowIdConflictPolicy workflowIdConflictPolicy,
      String staticSummary,
      String staticDetails,
      String requestId,
      List<Callback> completionCallbacks,
      List<Link> links,
      OnConflictOptions onConflictOptions,
      Priority priority,
      VersioningOverride versioningOverride) {
    this.workflowId = workflowId;
    this.workflowIdReusePolicy = workflowIdReusePolicy;
    this.workflowRunTimeout = workflowRunTimeout;
    this.workflowExecutionTimeout = workflowExecutionTimeout;
    this.workflowTaskTimeout = workflowTaskTimeout;
    this.taskQueue = taskQueue;
    this.retryOptions = retryOptions;
    this.cronSchedule = cronSchedule;
    this.memo = memo;
    this.searchAttributes = searchAttributes;
    this.typedSearchAttributes = typedSearchAttributes;
    this.contextPropagators = contextPropagators;
    this.disableEagerExecution = disableEagerExecution;
    this.startDelay = startDelay;
    this.workflowIdConflictPolicy = workflowIdConflictPolicy;
    this.staticSummary = staticSummary;
    this.staticDetails = staticDetails;
    this.requestId = requestId;
    this.completionCallbacks = completionCallbacks;
    this.links = links;
    this.onConflictOptions = onConflictOptions;
    this.priority = priority;
    this.versioningOverride = versioningOverride;
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

  public Map<String, Object> getMemo() {
    return memo;
  }

  /**
   * @deprecated use {@link #getTypedSearchAttributes} instead.
   */
  @Deprecated
  public Map<String, ?> getSearchAttributes() {
    return searchAttributes;
  }

  public SearchAttributes getTypedSearchAttributes() {
    return typedSearchAttributes;
  }

  /**
   * @return the list of context propagators to use during this workflow. This list overrides the
   *     list specified on {@link WorkflowClientOptions#getContextPropagators()}, {@code null} means
   *     no overriding
   */
  public @Nullable List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  public boolean isDisableEagerExecution() {
    return disableEagerExecution;
  }

  public @Nullable Duration getStartDelay() {
    return startDelay;
  }

  public WorkflowIdConflictPolicy getWorkflowIdConflictPolicy() {
    return workflowIdConflictPolicy;
  }

  @Experimental
  public String getRequestId() {
    return requestId;
  }

  @Experimental
  public @Nullable List<Callback> getCompletionCallbacks() {
    return completionCallbacks;
  }

  @Experimental
  public @Nullable List<Link> getLinks() {
    return links;
  }

  @Experimental
  public String getStaticSummary() {
    return staticSummary;
  }

  @Experimental
  public String getStaticDetails() {
    return staticDetails;
  }

  @Experimental
  public @Nullable OnConflictOptions getOnConflictOptions() {
    return onConflictOptions;
  }

  @Experimental
  public Priority getPriority() {
    return priority;
  }

  @Experimental
  public VersioningOverride getVersioningOverride() {
    return versioningOverride;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowOptions that = (WorkflowOptions) o;
    return Objects.equal(workflowId, that.workflowId)
        && workflowIdReusePolicy == that.workflowIdReusePolicy
        && Objects.equal(workflowRunTimeout, that.workflowRunTimeout)
        && Objects.equal(workflowExecutionTimeout, that.workflowExecutionTimeout)
        && Objects.equal(workflowTaskTimeout, that.workflowTaskTimeout)
        && Objects.equal(taskQueue, that.taskQueue)
        && Objects.equal(retryOptions, that.retryOptions)
        && Objects.equal(cronSchedule, that.cronSchedule)
        && Objects.equal(memo, that.memo)
        && Objects.equal(searchAttributes, that.searchAttributes)
        && Objects.equal(typedSearchAttributes, that.typedSearchAttributes)
        && Objects.equal(contextPropagators, that.contextPropagators)
        && Objects.equal(disableEagerExecution, that.disableEagerExecution)
        && Objects.equal(startDelay, that.startDelay)
        && Objects.equal(workflowIdConflictPolicy, that.workflowIdConflictPolicy)
        && Objects.equal(staticSummary, that.staticSummary)
        && Objects.equal(staticDetails, that.staticDetails)
        && Objects.equal(requestId, that.requestId)
        && Objects.equal(completionCallbacks, that.completionCallbacks)
        && Objects.equal(links, that.links)
        && Objects.equal(onConflictOptions, that.onConflictOptions)
        && Objects.equal(priority, that.priority)
        && Objects.equal(versioningOverride, that.versioningOverride);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        workflowId,
        workflowIdReusePolicy,
        workflowRunTimeout,
        workflowExecutionTimeout,
        workflowTaskTimeout,
        taskQueue,
        retryOptions,
        cronSchedule,
        memo,
        searchAttributes,
        typedSearchAttributes,
        contextPropagators,
        disableEagerExecution,
        startDelay,
        workflowIdConflictPolicy,
        staticSummary,
        staticDetails,
        requestId,
        completionCallbacks,
        links,
        onConflictOptions,
        priority,
        versioningOverride);
  }

  @Override
  public String toString() {
    return "WorkflowOptions{"
        + "workflowId='"
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
        + ", memo="
        + memo
        + ", searchAttributes="
        + searchAttributes
        + ", typedSearchAttributes="
        + typedSearchAttributes
        + ", contextPropagators="
        + contextPropagators
        + ", disableEagerExecution="
        + disableEagerExecution
        + ", startDelay="
        + startDelay
        + ", workflowIdConflictPolicy="
        + workflowIdConflictPolicy
        + ", staticSummary="
        + staticSummary
        + ", staticDetails="
        + staticDetails
        + ", requestId="
        + requestId
        + ", completionCallbacks="
        + completionCallbacks
        + ", links="
        + links
        + ", onConflictOptions="
        + onConflictOptions
        + ", priority="
        + priority
        + ", versioningOverride="
        + versioningOverride
        + '}';
  }
}
