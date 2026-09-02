package io.temporal.internal.worker;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;
import static io.temporal.serviceclient.MetricsTag.TASK_FAILURE_TYPE;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageOrBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.command.v1.*;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.QueryResultType;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.errordetails.v1.WorkflowTaskCompletionBufferLostFailure;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.internal.payload.visitor.MessageVisitor;
import io.temporal.internal.retryer.GrpcMessageTooLargeException;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.StatusUtils;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.*;
import io.temporal.worker.tuning.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

final class WorkflowWorker implements SuspendableWorker {
  private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);

  // Backoff between resends of a paginated completion after the server reports its buffered pages
  // were lost. Buffer loss is transient; the loop is bounded by the server eventually timing the
  // task
  // out (after which the stale token fails with a different error) or by worker shutdown.
  private static final long WFT_COMPLETION_PAGE_RESEND_INITIAL_BACKOFF_MS = 100;
  private static final long WFT_COMPLETION_PAGE_RESEND_MAX_BACKOFF_MS = 5000;

  private final WorkflowRunLockManager runLocks;

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private final WorkflowExecutorCache cache;
  private final WorkflowTaskHandler handler;
  private final String stickyTaskQueueName;
  private PollerOptions pollerOptions;
  private final Scope workerMetricsScope;
  private final GrpcRetryer grpcRetryer;
  private final EagerActivityDispatcher eagerActivityDispatcher;
  private final int maxEagerActivityReservationsPerWorkflowTask;
  private final TrackingSlotSupplier<WorkflowSlotInfo> slotSupplier;

  private final TaskCounter taskCounter = new TaskCounter();
  private final PollerTracker pollerTracker = new PollerTracker();
  private final PollerTracker stickyPollerTracker = new PollerTracker();
  private final NamespaceCapabilities namespaceCapabilities;

  private PollTaskExecutor<WorkflowTask> pollTaskExecutor;

  // TODO this ideally should be volatile or final (and NoopWorker should go away)
  //  Currently the implementation looks safe without volatile, but it's brittle.
  @Nonnull private SuspendableWorker poller = new NoopWorker();

  private DisableNormalPolling stickyQueueBalancer;

  public WorkflowWorker(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nullable String stickyTaskQueueName,
      @Nonnull SingleWorkerOptions options,
      @Nonnull WorkflowRunLockManager runLocks,
      @Nonnull WorkflowExecutorCache cache,
      @Nonnull WorkflowTaskHandler handler,
      @Nonnull EagerActivityDispatcher eagerActivityDispatcher,
      int maxEagerActivityReservationsPerWorkflowTask,
      @Nonnull SlotSupplier<WorkflowSlotInfo> slotSupplier,
      @Nonnull NamespaceCapabilities namespaceCapabilities) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.options = Objects.requireNonNull(options);
    this.stickyTaskQueueName = stickyTaskQueueName;
    this.pollerOptions = getPollerOptions(options);
    this.workerMetricsScope =
        MetricsTag.tagged(options.getMetricsScope(), WorkerMetricsTag.WorkerType.WORKFLOW_WORKER);
    this.runLocks = Objects.requireNonNull(runLocks);
    this.cache = Objects.requireNonNull(cache);
    this.handler = Objects.requireNonNull(handler);
    this.grpcRetryer = new GrpcRetryer(service.getServerCapabilities());
    this.eagerActivityDispatcher = eagerActivityDispatcher;
    this.maxEagerActivityReservationsPerWorkflowTask = maxEagerActivityReservationsPerWorkflowTask;
    this.slotSupplier = new TrackingSlotSupplier<>(slotSupplier, this.workerMetricsScope);
    this.namespaceCapabilities = namespaceCapabilities;
  }

  @Override
  public boolean start() {
    if (handler.isAnyTypeSupported()) {
      // Auto-enroll into poller autoscaling if the namespace advertises the capability and this
      // poller type was left at its default. Resolved here (after namespace capabilities are known)
      // so the poller built below reflects the effective behavior.
      this.pollerOptions =
          PollerOptions.maybeEnrollInPollerAutoscaling(pollerOptions, namespaceCapabilities);
      pollTaskExecutor =
          new PollTaskExecutor<>(
              namespace,
              taskQueue,
              options.getIdentity(),
              new TaskHandlerImpl(handler),
              pollerOptions,
              this.slotSupplier.maximumSlots().orElse(Integer.MAX_VALUE),
              options.isUsingVirtualThreads());

      boolean useAsyncPoller =
          pollerOptions.getPollerBehavior() instanceof PollerBehaviorAutoscaling;
      if (useAsyncPoller) {
        List<AsyncPoller.PollTaskAsync<WorkflowTask>> pollers;
        if (stickyTaskQueueName != null) {
          AsyncWorkflowPollTask normalPoller =
              new AsyncWorkflowPollTask(
                  service,
                  namespace,
                  taskQueue,
                  null,
                  options.getIdentity(),
                  options.getWorkerInstanceKey(),
                  options.getWorkerVersioningOptions(),
                  slotSupplier,
                  workerMetricsScope,
                  service.getServerCapabilities(),
                  pollerTracker,
                  workerControlTaskQueue());
          pollers =
              Arrays.asList(
                  new AsyncWorkflowPollTask(
                      service,
                      namespace,
                      taskQueue,
                      stickyTaskQueueName,
                      options.getIdentity(),
                      options.getWorkerInstanceKey(),
                      options.getWorkerVersioningOptions(),
                      slotSupplier,
                      workerMetricsScope,
                      service.getServerCapabilities(),
                      stickyPollerTracker,
                      workerControlTaskQueue()),
                  normalPoller);
          this.stickyQueueBalancer = normalPoller;
        } else {
          pollers =
              Collections.singletonList(
                  new AsyncWorkflowPollTask(
                      service,
                      namespace,
                      taskQueue,
                      null,
                      options.getIdentity(),
                      options.getWorkerInstanceKey(),
                      options.getWorkerVersioningOptions(),
                      slotSupplier,
                      workerMetricsScope,
                      service.getServerCapabilities(),
                      pollerTracker,
                      workerControlTaskQueue()));
        }
        poller =
            new AsyncPoller<>(
                slotSupplier,
                new SlotReservationData(taskQueue, options.getIdentity(), options.getBuildId()),
                pollers,
                this.pollTaskExecutor,
                pollerOptions,
                namespaceCapabilities,
                workerMetricsScope);
      } else {
        PollerBehaviorSimpleMaximum pollerBehavior =
            (PollerBehaviorSimpleMaximum) pollerOptions.getPollerBehavior();
        StickyQueueBalancer stickyQueueBalancer =
            new StickyQueueBalancer(
                pollerBehavior.getMaxConcurrentTaskPollers(), stickyTaskQueueName != null);
        this.stickyQueueBalancer = stickyQueueBalancer;
        poller =
            new MultiThreadedPoller<>(
                options.getIdentity(),
                new WorkflowPollTask(
                    service,
                    namespace,
                    taskQueue,
                    stickyTaskQueueName,
                    options.getIdentity(),
                    options.getWorkerInstanceKey(),
                    options.getWorkerVersioningOptions(),
                    slotSupplier,
                    stickyQueueBalancer,
                    workerMetricsScope,
                    service.getServerCapabilities(),
                    pollerTracker,
                    stickyPollerTracker,
                    workerControlTaskQueue()),
                pollTaskExecutor,
                pollerOptions,
                workerMetricsScope,
                namespaceCapabilities);
      }
      poller.start();
      workerMetricsScope.counter(MetricsType.WORKER_START_COUNTER).inc(1);

      return true;
    } else {
      return false;
    }
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    String supplierName = this + "#executorSlots";

    boolean stickyQueueBalancerDrainEnabled =
        !interruptTasks
            && !options.getDrainStickyTaskQueueTimeout().isZero()
            && stickyTaskQueueName != null
            && stickyQueueBalancer != null
            && !namespaceCapabilities.isGracefulPollShutdown();

    CompletableFuture<Void> pollerShutdown =
        CompletableFuture.completedFuture(null)
            .thenCompose(
                ignore ->
                    stickyQueueBalancerDrainEnabled
                        ? shutdownManager.waitForStickyQueueBalancer(
                            stickyQueueBalancer, options.getDrainStickyTaskQueueTimeout())
                        : CompletableFuture.completedFuture(null))
            .thenCompose(ignore -> poller.shutdown(shutdownManager, interruptTasks));
    return pollerShutdown
        .thenCompose(
            ignore ->
                !interruptTasks
                    ? shutdownManager.waitForSupplierPermitsReleasedUnlimited(
                        slotSupplier, supplierName)
                    : CompletableFuture.completedFuture(null))
        .thenCompose(
            ignore ->
                pollTaskExecutor != null
                    ? pollTaskExecutor.shutdown(shutdownManager, interruptTasks)
                    : CompletableFuture.completedFuture(null))
        .exceptionally(
            e -> {
              log.error("Unexpected exception during shutdown", e);
              return null;
            });
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = ShutdownManager.awaitTermination(poller, unit.toMillis(timeout));
    // relies on the fact that the pollTaskExecutor is the last one to be shutdown, no need to
    // wait separately for intermediate steps
    ShutdownManager.awaitTermination(pollTaskExecutor, timeoutMillis);
  }

  @Override
  public void suspendPolling() {
    poller.suspendPolling();
  }

  @Override
  public void resumePolling() {
    poller.resumePolling();
  }

  @Override
  public boolean isSuspended() {
    return poller.isSuspended();
  }

  @Override
  public boolean isShutdown() {
    return poller.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return poller.isTerminated() && (pollTaskExecutor == null || pollTaskExecutor.isTerminated());
  }

  @Override
  public WorkerLifecycleState getLifecycleState() {
    return poller.getLifecycleState();
  }

  private PollerOptions getPollerOptions(SingleWorkerOptions options) {
    PollerOptions pollerOptions = options.getPollerOptions();
    if (pollerOptions.getPollThreadNamePrefix() == null) {
      pollerOptions =
          PollerOptions.newBuilder(pollerOptions)
              .setPollThreadNamePrefix(
                  WorkerThreadsNameHelper.getWorkflowPollerThreadPrefix(namespace, taskQueue))
              .build();
    }
    return pollerOptions;
  }

  @Nullable
  public WorkflowTaskDispatchHandle reserveWorkflowExecutor() {
    // to avoid pollTaskExecutor becoming null inside the lambda, we cache it here
    final PollTaskExecutor<WorkflowTask> executor = pollTaskExecutor;
    if (executor == null || isSuspended()) {
      return null;
    }
    return slotSupplier
        .tryReserveSlot(
            new SlotReservationData(taskQueue, options.getIdentity(), options.getBuildId()))
        .map(
            slotPermit ->
                new WorkflowTaskDispatchHandle(
                    workflowTask -> {
                      String queueName =
                          workflowTask.getResponse().getWorkflowExecutionTaskQueue().getName();
                      TaskQueueKind queueKind =
                          workflowTask.getResponse().getWorkflowExecutionTaskQueue().getKind();
                      Preconditions.checkArgument(
                          this.taskQueue.equals(queueName)
                              || TaskQueueKind.TASK_QUEUE_KIND_STICKY.equals(queueKind)
                                  && this.stickyTaskQueueName.equals(queueName),
                          "Got a WFT for a wrong queue %s, expected %s or %s",
                          queueName,
                          this.taskQueue,
                          this.stickyTaskQueueName);
                      try {
                        pollTaskExecutor.process(workflowTask);
                        return true;
                      } catch (RejectedExecutionException e) {
                        return false;
                      }
                    },
                    slotSupplier,
                    slotPermit,
                    options.getDeploymentOptions()))
        .orElse(null);
  }

  public TrackingSlotSupplier<WorkflowSlotInfo> getSlotSupplier() {
    return slotSupplier;
  }

  public boolean hasStickyQueue() {
    return stickyTaskQueueName != null;
  }

  public String getStickyTaskQueueName() {
    return stickyTaskQueueName;
  }

  public TaskCounter getTaskCounter() {
    return taskCounter;
  }

  public PollerOptions getPollerOptions() {
    return pollerOptions;
  }

  public PollerTracker getPollerTracker() {
    return pollerTracker;
  }

  public PollerTracker getStickyPollerTracker() {
    return stickyPollerTracker;
  }

  @Override
  public String toString() {
    return String.format(
        "WorkflowWorker{identity=%s, namespace=%s, taskQueue=%s}",
        options.getIdentity(), namespace, taskQueue);
  }

  private void storeOutboundPayloads(
      com.google.protobuf.Message.Builder builder, @Nullable StorageDriverTargetInfo target) {
    storeOutboundPayloads(builder, target, null);
  }

  private void storeOutboundPayloads(
      com.google.protobuf.Message.Builder builder,
      @Nullable StorageDriverTargetInfo target,
      @Nullable MessageVisitor<StorageDriverTargetInfo> targetVisitor) {
    ExternalStorageRunner externalStorageRunner = options.getExternalStorageRunner();
    if (externalStorageRunner == null) {
      return;
    }
    try {
      externalStorageRunner.store(builder, target, targetVisitor, options.getStorageCancellation());
    } catch (CancellationException e) {
      // if the worker is shutting down, extstore will throw a CancellationException and we need to
      // rethrow it here so the handle() method can decide what to do.
      throw e;
    } catch (Exception e) {
      throw new ExternalStorageTaskFailure("External storage store failed", e);
    }
  }

  private static final class ExternalStorageTaskFailure extends RuntimeException {
    ExternalStorageTaskFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  @Nullable
  private StorageDriverTargetInfo parentStorageTarget(@Nullable WorkflowExecution parent) {
    if (parent == null || options.getExternalStorageRunner() == null) {
      return null;
    }
    return new StorageDriverWorkflowInfo(
        namespace,
        Strings.emptyToNull(parent.getWorkflowId()),
        Strings.emptyToNull(parent.getRunId()),
        null);
  }

  @Nullable
  private StorageDriverTargetInfo workflowStorageTarget(
      WorkflowExecution execution, String workflowType) {
    if (options.getExternalStorageRunner() == null) {
      return null;
    }
    return new StorageDriverWorkflowInfo(
        namespace, execution.getWorkflowId(), execution.getRunId(), workflowType);
  }

  static StorageDriverTargetInfo deriveStorageTarget(
      String namespace, StorageDriverTargetInfo current, MessageOrBuilder message) {
    return deriveStorageTarget(namespace, current, message, null);
  }

  static StorageDriverTargetInfo deriveStorageTarget(
      String namespace,
      StorageDriverTargetInfo current,
      MessageOrBuilder message,
      @Nullable StorageDriverTargetInfo completionTarget) {
    if (!(message instanceof CommandOrBuilder)) {
      return current;
    }
    CommandOrBuilder command = (CommandOrBuilder) message;
    // Keep this exhaustive so new command attributes require an explicit target decision.
    switch (command.getAttributesCase()) {
      case START_CHILD_WORKFLOW_EXECUTION_COMMAND_ATTRIBUTES:
        StartChildWorkflowExecutionCommandAttributesOrBuilder child =
            command.getStartChildWorkflowExecutionCommandAttributesOrBuilder();
        return new StorageDriverWorkflowInfo(
            namespace, child.getWorkflowId(), null, child.getWorkflowType().getName());
      case SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_COMMAND_ATTRIBUTES:
        WorkflowExecution execution =
            command.getSignalExternalWorkflowExecutionCommandAttributes().getExecution();
        return new StorageDriverWorkflowInfo(
            namespace, execution.getWorkflowId(), execution.getRunId(), null);
      case CONTINUE_AS_NEW_WORKFLOW_EXECUTION_COMMAND_ATTRIBUTES:
        if (current instanceof StorageDriverWorkflowInfo) {
          ContinueAsNewWorkflowExecutionCommandAttributesOrBuilder continueAsNew =
              command.getContinueAsNewWorkflowExecutionCommandAttributesOrBuilder();
          StorageDriverWorkflowInfo currentWorkflow = (StorageDriverWorkflowInfo) current;
          String workflowType = continueAsNew.getWorkflowType().getName();
          return new StorageDriverWorkflowInfo(
              namespace,
              currentWorkflow.getId(),
              null,
              Strings.isNullOrEmpty(workflowType) ? currentWorkflow.getType() : workflowType);
        }
        return current;
      case COMPLETE_WORKFLOW_EXECUTION_COMMAND_ATTRIBUTES:
        return completionTarget != null ? completionTarget : current;
      case SCHEDULE_ACTIVITY_TASK_COMMAND_ATTRIBUTES:
      case ATTRIBUTES_NOT_SET:
      case START_TIMER_COMMAND_ATTRIBUTES:
      case FAIL_WORKFLOW_EXECUTION_COMMAND_ATTRIBUTES:
      case REQUEST_CANCEL_ACTIVITY_TASK_COMMAND_ATTRIBUTES:
      case CANCEL_TIMER_COMMAND_ATTRIBUTES:
      case CANCEL_WORKFLOW_EXECUTION_COMMAND_ATTRIBUTES:
      case REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_COMMAND_ATTRIBUTES:
      case RECORD_MARKER_COMMAND_ATTRIBUTES:
      case UPSERT_WORKFLOW_SEARCH_ATTRIBUTES_COMMAND_ATTRIBUTES:
      case PROTOCOL_MESSAGE_COMMAND_ATTRIBUTES:
      case MODIFY_WORKFLOW_PROPERTIES_COMMAND_ATTRIBUTES:
      case SCHEDULE_NEXUS_OPERATION_COMMAND_ATTRIBUTES:
      case REQUEST_CANCEL_NEXUS_OPERATION_COMMAND_ATTRIBUTES:
        return current;
    }
    throw new IllegalStateException("Unhandled command attributes: " + command.getAttributesCase());
  }

  private class TaskHandlerImpl implements PollTaskExecutor.TaskHandler<WorkflowTask> {

    final WorkflowTaskHandler handler;

    private TaskHandlerImpl(WorkflowTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(WorkflowTask task) throws Exception {
      PollWorkflowTaskQueueResponse workflowTaskResponse = task.getResponse();
      WorkflowExecution workflowExecution = workflowTaskResponse.getWorkflowExecution();
      String runId = workflowExecution.getRunId();
      String workflowType = workflowTaskResponse.getWorkflowType().getName();

      Scope workflowTypeScope =
          workerMetricsScope.tagged(ImmutableMap.of(MetricsTag.WORKFLOW_TYPE, workflowType));

      MDC.put(LoggerTag.WORKFLOW_ID, workflowExecution.getWorkflowId());
      MDC.put(LoggerTag.WORKFLOW_TYPE, workflowType);
      MDC.put(LoggerTag.RUN_ID, runId);

      boolean locked = false;

      Stopwatch swTotal =
          workflowTypeScope.timer(MetricsType.WORKFLOW_TASK_EXECUTION_TOTAL_LATENCY).start();
      SlotReleaseReason releaseReason = SlotReleaseReason.taskComplete();
      try {
        if (!Strings.isNullOrEmpty(stickyTaskQueueName)) {
          // Serialize workflow task processing for a particular workflow run.
          // This is used to make sure that query tasks and real workflow tasks
          // are serialized when sticky is on.
          //
          // Acquiring a lock with a timeout to avoid having lots of workflow tasks for the same run
          // id waiting for a lock and consuming threads in case if lock is unavailable.
          //
          // Throws interrupted exception which is propagated. It's a correct way to handle it here.
          //
          // TODO 1: 5 seconds is chosen as a half of normal workflow task timeout.
          //   This value should be dynamically configured.
          // TODO 2: Does "consider increasing workflow task timeout" advice in this exception makes
          //   any sense?
          //   This MAYBE makes sense only if a previous workflow task timed out, it's still in
          //   progress on the worker and the next workflow task got picked up by the same exact
          //   worker from the general non-sticky task queue.
          //   Even in this case, this advice looks misleading, something else is going on
          //   (like an extreme network latency).
          locked = runLocks.tryLock(runId, 5, TimeUnit.SECONDS);

          if (!locked) {
            throw new UnableToAcquireLockException(
                "Workflow lock for the run id hasn't been released by one of previous execution attempts, "
                    + "consider increasing workflow task timeout.");
          }
        }

        Optional<PollWorkflowTaskQueueResponse> nextWFTResponse = Optional.of(workflowTaskResponse);
        do {
          PollWorkflowTaskQueueResponse currentTask = nextWFTResponse.get();
          nextWFTResponse = Optional.empty();
          boolean iterationFailed = false;
          try {
            WorkflowTaskHandler.Result result = handleTask(currentTask, workflowTypeScope);
            WorkflowTaskFailedCause taskFailedCause = null;
            try {
              RespondWorkflowTaskCompletedRequest taskCompleted = result.getTaskCompleted();
              RespondWorkflowTaskFailedRequest taskFailed = result.getTaskFailed();
              RespondQueryTaskCompletedRequest queryCompleted = result.getQueryCompleted();

              if (queryCompleted != null) {
                try {
                  sendDirectQueryCompletedResponse(
                      currentTask.getTaskToken(),
                      queryCompleted.toBuilder(),
                      workflowTypeScope,
                      workflowStorageTarget(workflowExecution, workflowType));
                } catch (ExternalStorageTaskFailure e) {
                  Failure failure =
                      storageFailure(
                          workflowExecution.getWorkflowId(), e, "Failed to send query response");
                  RespondQueryTaskCompletedRequest.Builder queryFailedBuilder =
                      RespondQueryTaskCompletedRequest.newBuilder()
                          .setTaskToken(currentTask.getTaskToken())
                          .setNamespace(namespace)
                          .setCompletedType(QueryResultType.QUERY_RESULT_TYPE_FAILED)
                          .setErrorMessage(failure.getMessage())
                          .setFailure(failure);
                  sendDirectQueryCompletedResponse(
                      currentTask.getTaskToken(),
                      queryFailedBuilder,
                      workflowTypeScope,
                      workflowStorageTarget(workflowExecution, workflowType));
                } catch (StatusRuntimeException e) {
                  GrpcMessageTooLargeException tooLargeException =
                      GrpcMessageTooLargeException.tryWrap(e);
                  if (tooLargeException == null) {
                    throw e;
                  }
                  Failure failure =
                      grpcMessageTooLargeFailure(
                          workflowExecution.getWorkflowId(),
                          tooLargeException,
                          "Failed to send query response");
                  RespondQueryTaskCompletedRequest.Builder queryFailedBuilder =
                      RespondQueryTaskCompletedRequest.newBuilder()
                          .setTaskToken(currentTask.getTaskToken())
                          .setNamespace(namespace)
                          .setCompletedType(QueryResultType.QUERY_RESULT_TYPE_FAILED)
                          .setErrorMessage(failure.getMessage())
                          .setFailure(failure);
                  sendDirectQueryCompletedResponse(
                      currentTask.getTaskToken(),
                      queryFailedBuilder,
                      workflowTypeScope,
                      workflowStorageTarget(workflowExecution, workflowType));
                }
              } else {
                try {
                  WorkflowTaskFailedCause requestTooLargeCause =
                      taskCompleted == null
                          ? null
                          : completionExceedingSizeLimitCause(taskCompleted);
                  if (requestTooLargeCause != null) {
                    // A completion whose recombined command bytes exceed the namespace limit would
                    // be
                    // rejected and the workflow terminated by the server, so fail it proactively
                    // rather than sending doomed pages.
                    taskFailedCause = requestTooLargeCause;
                    RespondWorkflowTaskFailedRequest.Builder taskFailedBuilder =
                        RespondWorkflowTaskFailedRequest.newBuilder()
                            .setFailure(
                                requestTooLargeFailure(
                                    workflowExecution.getWorkflowId(), taskCompleted))
                            .setCause(requestTooLargeCause);
                    sendTaskFailed(
                        currentTask.getTaskToken(),
                        taskFailedBuilder,
                        result.getRequestRetryOptions(),
                        workflowTypeScope);
                  } else if (taskCompleted != null) {
                    RespondWorkflowTaskCompletedRequest.Builder requestBuilder =
                        taskCompleted.toBuilder();
                    try (EagerActivitySlotsReservation activitySlotsReservation =
                        new EagerActivitySlotsReservation(
                            eagerActivityDispatcher, maxEagerActivityReservationsPerWorkflowTask)) {
                      activitySlotsReservation.applyToRequest(requestBuilder);
                      RespondWorkflowTaskCompletedResponse response =
                          sendTaskCompleted(
                              currentTask.getTaskToken(),
                              requestBuilder,
                              result.getRequestRetryOptions(),
                              workflowTypeScope,
                              workflowStorageTarget(workflowExecution, workflowType),
                              parentStorageTarget(result.getCompletionParentExecution()));
                      // If we were processing a speculative WFT the server may instruct us that the
                      // task was dropped by resting out event ID.
                      long resetEventId = response.getResetHistoryEventId();
                      if (resetEventId != 0) {
                        result.getResetEventIdHandle().apply(resetEventId);
                      }
                      nextWFTResponse =
                          response.hasWorkflowTask()
                              ? Optional.of(response.getWorkflowTask())
                              : Optional.empty();
                      // TODO we don't have to do this under the runId lock
                      activitySlotsReservation.handleResponse(response);
                    }
                  } else if (taskFailed != null) {
                    taskFailedCause = taskFailed.getCause();
                    sendTaskFailed(
                        currentTask.getTaskToken(),
                        taskFailed.toBuilder(),
                        result.getRequestRetryOptions(),
                        workflowTypeScope,
                        workflowStorageTarget(workflowExecution, workflowType));
                  }

                  // Apply post-completion metrics only if runnable present and the above succeeded
                  if (result.getApplyPostCompletionMetrics() != null) {
                    result.getApplyPostCompletionMetrics().run();
                  }
                } catch (GrpcMessageTooLargeException e) {
                  // Only fail workflow task on the first attempt, subsequent failures of the same
                  // workflow task should timeout.
                  if (currentTask.getAttempt() > 1) {
                    throw e;
                  }

                  releaseReason = SlotReleaseReason.error(e);
                  handleReportingFailure(
                      e, currentTask, result, workflowExecution, workflowTypeScope);
                  // setting/replacing failure cause for metrics purposes
                  taskFailedCause =
                      WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_GRPC_MESSAGE_TOO_LARGE;

                  String messagePrefix =
                      String.format(
                          "Failed to send workflow task %s",
                          taskFailed == null ? "completion" : "failure");
                  RespondWorkflowTaskFailedRequest.Builder taskFailedBuilder =
                      RespondWorkflowTaskFailedRequest.newBuilder()
                          .setFailure(
                              grpcMessageTooLargeFailure(
                                  workflowExecution.getWorkflowId(), e, messagePrefix))
                          .setCause(
                              WorkflowTaskFailedCause
                                  .WORKFLOW_TASK_FAILED_CAUSE_GRPC_MESSAGE_TOO_LARGE);
                  sendTaskFailed(
                      currentTask.getTaskToken(),
                      taskFailedBuilder,
                      result.getRequestRetryOptions(),
                      workflowTypeScope,
                      workflowStorageTarget(workflowExecution, workflowType));
                } catch (ExternalStorageTaskFailure e) {
                  releaseReason = SlotReleaseReason.error(e);
                  handleReportingFailure(
                      e, currentTask, result, workflowExecution, workflowTypeScope);
                  taskFailedCause =
                      WorkflowTaskFailedCause
                          .WORKFLOW_TASK_FAILED_CAUSE_WORKFLOW_WORKER_UNHANDLED_FAILURE;

                  String messagePrefix =
                      String.format(
                          "Failed to send workflow task %s",
                          taskFailed == null ? "completion" : "failure");
                  RespondWorkflowTaskFailedRequest.Builder storageFailedBuilder =
                      RespondWorkflowTaskFailedRequest.newBuilder()
                          .setFailure(
                              storageFailure(workflowExecution.getWorkflowId(), e, messagePrefix))
                          .setCause(
                              WorkflowTaskFailedCause
                                  .WORKFLOW_TASK_FAILED_CAUSE_WORKFLOW_WORKER_UNHANDLED_FAILURE);
                  sendTaskFailed(
                      currentTask.getTaskToken(),
                      storageFailedBuilder,
                      result.getRequestRetryOptions(),
                      workflowTypeScope,
                      workflowStorageTarget(workflowExecution, workflowType));
                }
              }
            } catch (CancellationException e) {
              if (!options.getStorageCancellation().isCancellationRequested()) {
                throw e;
              }
              log.trace("Abandoned a workflow task while the worker was shutting down", e);
              return;
            } catch (Exception e) {
              iterationFailed = true;
              releaseReason = SlotReleaseReason.error(e);
              handleReportingFailure(e, currentTask, result, workflowExecution, workflowTypeScope);
              throw e;
            }

            if (taskFailedCause != null) {
              iterationFailed = true;
              String taskFailureType;
              switch (taskFailedCause) {
                case WORKFLOW_TASK_FAILED_CAUSE_NON_DETERMINISTIC_ERROR:
                  taskFailureType = MetricsTag.TASK_FAILURE_VALUE_NON_DETERMINISM_ERROR;
                  break;
                case WORKFLOW_TASK_FAILED_CAUSE_GRPC_MESSAGE_TOO_LARGE:
                  taskFailureType = MetricsTag.TASK_FAILURE_VALUE_GRPC_MESSAGE_TOO_LARGE;
                  break;
                case WORKFLOW_TASK_FAILED_CAUSE_REQUEST_TOO_LARGE:
                  taskFailureType = MetricsTag.TASK_FAILURE_VALUE_REQUEST_TOO_LARGE;
                  break;
                default:
                  taskFailureType = MetricsTag.TASK_FAILURE_VALUE_WORKFLOW_ERROR;
              }
              Scope workflowTaskFailureScope =
                  workflowTypeScope.tagged(ImmutableMap.of(TASK_FAILURE_TYPE, taskFailureType));
              // we don't trigger the counter in case of the legacy query
              // (which never has taskFailed set)
              workflowTaskFailureScope
                  .counter(MetricsType.WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER)
                  .inc(1);
            }
            if (nextWFTResponse.isPresent()) {
              workflowTypeScope.counter(MetricsType.WORKFLOW_TASK_HEARTBEAT_COUNTER).inc(1);
            }
          } catch (Exception e) {
            if (e instanceof CancellationException
                && options.getStorageCancellation().isCancellationRequested()) {
              log.trace("Abandoned a workflow task while the worker was shutting down", e);
              return;
            }
            iterationFailed = true;
            throw e;
          } finally {
            taskCounter.recordProcessed();
            if (iterationFailed) {
              taskCounter.recordFailed();
            }
          }
        } while (nextWFTResponse.isPresent());
      } finally {
        swTotal.stop();
        task.getCompletionCallback().apply(releaseReason);
        MDC.remove(LoggerTag.WORKFLOW_ID);
        MDC.remove(LoggerTag.WORKFLOW_TYPE);
        MDC.remove(LoggerTag.RUN_ID);

        if (locked) {
          runLocks.unlock(runId);
        }
      }
    }

    @Override
    public Throwable wrapFailure(WorkflowTask task, Throwable failure) {
      WorkflowExecution execution = task.getResponse().getWorkflowExecution();
      return new RuntimeException(
          "Failure processing workflow task. WorkflowId="
              + execution.getWorkflowId()
              + ", RunId="
              + execution.getRunId()
              + ", WorkflowType="
              + task.getResponse().getWorkflowType().getName()
              + ", Attempt="
              + task.getResponse().getAttempt(),
          failure);
    }

    private WorkflowTaskHandler.Result handleTask(
        PollWorkflowTaskQueueResponse task, Scope workflowTypeMetricsScope) throws Exception {
      Stopwatch sw =
          workflowTypeMetricsScope.timer(MetricsType.WORKFLOW_TASK_EXECUTION_LATENCY).start();
      try {
        return handler.handleWorkflowTask(task);
      } catch (Throwable e) {
        workflowTypeMetricsScope.counter(MetricsType.WORKFLOW_TASK_NO_COMPLETION_COUNTER).inc(1);
        // Make sure that the task failure metric has the correct type
        Scope workflowTaskFailureScope = workflowTypeMetricsScope;
        if (e instanceof NonDeterministicException) {
          workflowTaskFailureScope =
              workflowTaskFailureScope.tagged(
                  ImmutableMap.of(
                      TASK_FAILURE_TYPE, MetricsTag.TASK_FAILURE_VALUE_NON_DETERMINISM_ERROR));
        } else {
          workflowTaskFailureScope =
              workflowTaskFailureScope.tagged(
                  ImmutableMap.of(TASK_FAILURE_TYPE, MetricsTag.TASK_FAILURE_VALUE_WORKFLOW_ERROR));
        }
        // more detailed logging that we can do here is already done inside `handler`
        workflowTaskFailureScope
            .counter(MetricsType.WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER)
            .inc(1);
        throw e;
      } finally {
        sw.stop();
      }
    }

    @SuppressWarnings("deprecation")
    private RespondWorkflowTaskCompletedResponse sendTaskCompleted(
        ByteString taskToken,
        RespondWorkflowTaskCompletedRequest.Builder taskCompleted,
        RpcRetryOptions retryOptions,
        Scope workflowTypeMetricsScope,
        @Nullable StorageDriverTargetInfo storageTarget,
        @Nullable StorageDriverTargetInfo completionTarget) {
      taskCompleted
          .setIdentity(options.getIdentity())
          .setNamespace(namespace)
          .setTaskToken(taskToken);
      String workerControlTaskQueue = workerControlTaskQueue();
      if (workerControlTaskQueue != null) {
        taskCompleted.setWorkerControlTaskQueue(workerControlTaskQueue);
      }

      if (options.getDeploymentOptions() != null) {
        taskCompleted.setDeploymentOptions(
            WorkerVersioningProtoUtils.deploymentOptionsToProto(options.getDeploymentOptions()));
      } else if (service.getServerCapabilities().get().getBuildIdBasedVersioning()) {
        taskCompleted.setWorkerVersionStamp(options.workerVersionStamp());
      } else {
        taskCompleted.setBinaryChecksum(options.getBuildId());
      }

      MessageVisitor<StorageDriverTargetInfo> storageTargetVisitor =
          (current, message) -> deriveStorageTarget(namespace, current, message, completionTarget);
      storeOutboundPayloads(taskCompleted, storageTarget, storageTargetVisitor);
      RespondWorkflowTaskCompletedRequest request = taskCompleted.build();
      GrpcRetryer.GrpcRetryerOptions grpcRetryOptions =
          new GrpcRetryer.GrpcRetryerOptions(
              RpcRetryOptions.newBuilder().buildWithDefaultsFrom(retryOptions), null);

      if (!namespaceCapabilities.isWorkflowTaskCompletionPagination()) {
        return grpcRetryer.retryWithResult(
            () -> respondWorkflowTaskCompleted(request, workflowTypeMetricsScope),
            grpcRetryOptions);
      }

      WorkflowTaskCompletionPaginator.Pages pages =
          WorkflowTaskCompletionPaginator.paginate(
              request, WorkflowTaskCompletionPaginator.MAX_PAGE_BYTES);
      if (!pages.isPaginated()) {
        return grpcRetryer.retryWithResult(
            () -> respondWorkflowTaskCompleted(pages.finalPage, workflowTypeMetricsScope),
            grpcRetryOptions);
      }
      return sendPaginatedTaskCompleted(pages, retryOptions, workflowTypeMetricsScope);
    }

    /**
     * Sends a paginated completion, resending every page from page 0 on buffer loss. Buffer loss —
     * the server dropping the pages it had buffered for this token — is transient, so this backs
     * off and retries. The gRPC retry layer does not retry buffer loss (it is excluded via a
     * DoNotRetryItem below), so this loop is its sole handler; it bails on worker shutdown, and the
     * server bounds it by eventually timing the task out.
     */
    private RespondWorkflowTaskCompletedResponse sendPaginatedTaskCompleted(
        WorkflowTaskCompletionPaginator.Pages pages,
        RpcRetryOptions retryOptions,
        Scope workflowTypeMetricsScope) {
      // Buffer loss requires resending every page, which a single-page gRPC retry cannot do, so it
      // is handled by this loop instead of the retryer.
      GrpcRetryer.GrpcRetryerOptions pageRetryOptions =
          new GrpcRetryer.GrpcRetryerOptions(
              RpcRetryOptions.newBuilder(
                      RpcRetryOptions.newBuilder().buildWithDefaultsFrom(retryOptions))
                  .addDoNotRetry(Status.Code.ABORTED, WorkflowTaskCompletionBufferLostFailure.class)
                  .validateBuildWithDefaults(),
              null);
      long backoffMs = WFT_COMPLETION_PAGE_RESEND_INITIAL_BACKOFF_MS;
      while (true) {
        try {
          for (RespondWorkflowTaskCompletedRequest page : pages.intermediatePages) {
            grpcRetryer.retryWithResult(
                () -> respondWorkflowTaskCompleted(page, workflowTypeMetricsScope),
                pageRetryOptions);
          }
          return grpcRetryer.retryWithResult(
              () -> respondWorkflowTaskCompleted(pages.finalPage, workflowTypeMetricsScope),
              pageRetryOptions);
        } catch (StatusRuntimeException e) {
          if (!StatusUtils.hasFailure(e, WorkflowTaskCompletionBufferLostFailure.class)
              || isShutdown()) {
            throw e;
          }
          try {
            Thread.sleep(backoffMs);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw e;
          }
          backoffMs = Math.min(backoffMs * 2, WFT_COMPLETION_PAGE_RESEND_MAX_BACKOFF_MS);
        }
      }
    }

    private RespondWorkflowTaskCompletedResponse respondWorkflowTaskCompleted(
        RespondWorkflowTaskCompletedRequest request, Scope workflowTypeMetricsScope) {
      return service
          .blockingStub()
          .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, workflowTypeMetricsScope)
          .respondWorkflowTaskCompleted(request);
    }

    @SuppressWarnings("deprecation")
    private void sendTaskFailed(
        ByteString taskToken,
        RespondWorkflowTaskFailedRequest.Builder taskFailed,
        RpcRetryOptions retryOptions,
        Scope workflowTypeMetricsScope,
        @Nullable StorageDriverTargetInfo storageTarget) {
      GrpcRetryer.GrpcRetryerOptions grpcRetryOptions =
          new GrpcRetryer.GrpcRetryerOptions(
              RpcRetryOptions.newBuilder().buildWithDefaultsFrom(retryOptions), null);

      taskFailed.setIdentity(options.getIdentity()).setNamespace(namespace).setTaskToken(taskToken);

      if (options.getDeploymentOptions() != null) {
        taskFailed.setDeploymentOptions(
            WorkerVersioningProtoUtils.deploymentOptionsToProto(options.getDeploymentOptions()));
      } else if (service.getServerCapabilities().get().getBuildIdBasedVersioning()) {
        taskFailed.setWorkerVersion(options.workerVersionStamp());
      }

      storeOutboundPayloads(taskFailed, storageTarget);
      RespondWorkflowTaskFailedRequest request = taskFailed.build();
      grpcRetryer.retry(
          () ->
              service
                  .blockingStub()
                  .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, workflowTypeMetricsScope)
                  .respondWorkflowTaskFailed(request),
          grpcRetryOptions);
    }

    private void sendDirectQueryCompletedResponse(
        ByteString taskToken,
        RespondQueryTaskCompletedRequest.Builder queryCompleted,
        Scope workflowTypeMetricsScope,
        @Nullable StorageDriverTargetInfo storageTarget) {
      queryCompleted.setTaskToken(taskToken).setNamespace(namespace);
      storeOutboundPayloads(queryCompleted, storageTarget);
      RespondQueryTaskCompletedRequest request = queryCompleted.build();
      // Do not retry query response
      service
          .blockingStub()
          .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, workflowTypeMetricsScope)
          .respondQueryTaskCompleted(request);
    }

    private void logExceptionDuringResultReporting(
        Exception e, PollWorkflowTaskQueueResponse currentTask, WorkflowTaskHandler.Result result) {
      if (log.isDebugEnabled()) {
        log.debug(
            "Failure during reporting of workflow progress to the server. If seen continuously the workflow might be stuck. WorkflowId={}, RunId={}, startedEventId={}, WFTResult={}",
            currentTask.getWorkflowExecution().getWorkflowId(),
            currentTask.getWorkflowExecution().getRunId(),
            currentTask.getStartedEventId(),
            result,
            e);
      } else {
        log.warn(
            "Failure while reporting workflow progress to the server. If seen continuously the workflow might be stuck. WorkflowId={}, RunId={}, startedEventId={}",
            currentTask.getWorkflowExecution().getWorkflowId(),
            currentTask.getWorkflowExecution().getRunId(),
            currentTask.getStartedEventId(),
            e);
      }
    }

    private void handleReportingFailure(
        Exception e,
        PollWorkflowTaskQueueResponse currentTask,
        WorkflowTaskHandler.Result result,
        WorkflowExecution workflowExecution,
        Scope workflowTypeScope) {
      logExceptionDuringResultReporting(e, currentTask, result);
      // if we failed to report the workflow task completion back to the server,
      // our cached version of the workflow may be more advanced than the server is aware of.
      // We should discard this execution and perform a clean replay based on what server
      // knows next time.
      cache.invalidate(
          workflowExecution, workflowTypeScope, "Failed result reporting to the server", e);
    }

    /**
     * Returns the fail cause when {@code taskCompleted}'s recombined command bytes exceed the
     * namespace's completion size limit, or null otherwise. The limit governs the server's
     * recombined page buffer, so it only applies when pagination is enabled and the completion is
     * large enough to be paginated; a completion that fits in a single request is never buffered
     * and is left for the server to accept. Only command bytes count toward the limit, not messages
     * or metadata.
     */
    private WorkflowTaskFailedCause completionExceedingSizeLimitCause(
        RespondWorkflowTaskCompletedRequest taskCompleted) {
      if (!namespaceCapabilities.isWorkflowTaskCompletionPagination()
          || taskCompleted.getSerializedSize() <= WorkflowTaskCompletionPaginator.MAX_PAGE_BYTES) {
        return null;
      }
      long sizeLimit = namespaceCapabilities.getWorkflowTaskCompletionSizeLimit();
      if (sizeLimit <= 0) {
        return null;
      }
      long commandBytes = 0;
      for (Command command : taskCompleted.getCommandsList()) {
        commandBytes += command.getSerializedSize();
      }
      if (commandBytes <= sizeLimit) {
        return null;
      }
      return WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_REQUEST_TOO_LARGE;
    }

    private Failure requestTooLargeFailure(
        String workflowId, RespondWorkflowTaskCompletedRequest taskCompleted) {
      long commandBytes = 0;
      for (Command command : taskCompleted.getCommandsList()) {
        commandBytes += command.getSerializedSize();
      }
      String message =
          String.format(
              "Workflow task completion command size %d exceeds the namespace limit of %d bytes",
              commandBytes, namespaceCapabilities.getWorkflowTaskCompletionSizeLimit());
      ApplicationFailure applicationFailure =
          ApplicationFailure.newBuilder()
              .setMessage(message)
              .setType("WorkflowTaskCompletionRequestTooLarge")
              .build();
      applicationFailure.setStackTrace(new StackTraceElement[0]); // don't serialize stack trace
      return options
          .getDataConverter()
          .withContext(new WorkflowSerializationContext(namespace, workflowId))
          .exceptionToFailure(applicationFailure);
    }

    private Failure storageFailure(
        String workflowId, ExternalStorageTaskFailure e, String messagePrefix) {
      ApplicationFailure applicationFailure =
          ApplicationFailure.newBuilder()
              .setMessage(messagePrefix + ": " + (e.getCause() != null ? e.getCause() : e))
              .setType(ExternalStorageTaskFailure.class.getSimpleName())
              .build();
      applicationFailure.setStackTrace(new StackTraceElement[0]);
      return options
          .getDataConverter()
          .withContext(new WorkflowSerializationContext(namespace, workflowId))
          .exceptionToFailure(applicationFailure);
    }

    private Failure grpcMessageTooLargeFailure(
        String workflowId, GrpcMessageTooLargeException e, String messagePrefix) {
      ApplicationFailure applicationFailure =
          ApplicationFailure.newBuilder()
              .setMessage(messagePrefix + ": " + e.getMessage())
              .setType(GrpcMessageTooLargeException.class.getSimpleName())
              .build();
      applicationFailure.setStackTrace(new StackTraceElement[0]); // don't serialize stack trace
      return options
          .getDataConverter()
          .withContext(new WorkflowSerializationContext(namespace, workflowId))
          .exceptionToFailure(applicationFailure);
    }
  }

  private String workerControlTaskQueue() {
    return namespaceCapabilities.isWorkerCommands() ? options.getWorkerControlTaskQueue() : null;
  }
}
