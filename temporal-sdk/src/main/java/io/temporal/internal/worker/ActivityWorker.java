package io.temporal.internal.worker;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.Duration;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributesOrBuilder;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.activity.ActivityPollResponseToInfo;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.internal.worker.ActivityTaskHandler.Result;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.rpcretry.DefaultStubServiceOperationRpcRetryOptions;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.worker.tuning.*;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

final class ActivityWorker implements SuspendableWorker {
  private static final Logger log = LoggerFactory.getLogger(ActivityWorker.class);

  private SuspendableWorker poller = new NoopWorker();
  private PollTaskExecutor<ActivityTask> pollTaskExecutor;

  private final ActivityTaskHandler handler;
  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private final double taskQueueActivitiesPerSecond;
  private final PollerOptions pollerOptions;
  private final Scope workerMetricsScope;
  private final GrpcRetryer grpcRetryer;
  private final GrpcRetryer.GrpcRetryerOptions replyGrpcRetryerOptions;
  private final TrackingSlotSupplier<ActivitySlotInfo> slotSupplier;

  public ActivityWorker(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      double taskQueueActivitiesPerSecond,
      @Nonnull SingleWorkerOptions options,
      @Nonnull ActivityTaskHandler handler,
      @Nonnull SlotSupplier<ActivitySlotInfo> slotSupplier) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.handler = Objects.requireNonNull(handler);
    this.taskQueueActivitiesPerSecond = taskQueueActivitiesPerSecond;
    this.options = Objects.requireNonNull(options);
    this.pollerOptions = getPollerOptions(options);
    this.workerMetricsScope =
        MetricsTag.tagged(options.getMetricsScope(), WorkerMetricsTag.WorkerType.ACTIVITY_WORKER);
    this.grpcRetryer = new GrpcRetryer(service.getServerCapabilities());
    this.replyGrpcRetryerOptions =
        new GrpcRetryer.GrpcRetryerOptions(
            DefaultStubServiceOperationRpcRetryOptions.INSTANCE, null);

    this.slotSupplier = new TrackingSlotSupplier<>(slotSupplier, this.workerMetricsScope);
  }

  @Override
  public boolean start() {
    if (handler.isAnyTypeSupported()) {
      this.pollTaskExecutor =
          new PollTaskExecutor<>(
              namespace,
              taskQueue,
              options.getIdentity(),
              new TaskHandlerImpl(handler),
              pollerOptions,
              slotSupplier.maximumSlots().orElse(Integer.MAX_VALUE),
              options.isUsingVirtualThreads());

      boolean useAsyncPoller =
          pollerOptions.getPollerBehavior() instanceof PollerBehaviorAutoscaling;
      if (useAsyncPoller) {
        poller =
            new AsyncPoller<>(
                slotSupplier,
                new SlotReservationData(taskQueue, options.getIdentity(), options.getBuildId()),
                new AsyncActivityPollTask(
                    service,
                    namespace,
                    taskQueue,
                    options.getIdentity(),
                    options.getWorkerVersioningOptions(),
                    taskQueueActivitiesPerSecond,
                    this.slotSupplier,
                    workerMetricsScope,
                    service.getServerCapabilities()),
                this.pollTaskExecutor,
                pollerOptions,
                workerMetricsScope);

      } else {
        poller =
            new MultiThreadedPoller<>(
                options.getIdentity(),
                new ActivityPollTask(
                    service,
                    namespace,
                    taskQueue,
                    options.getIdentity(),
                    options.getWorkerVersioningOptions(),
                    taskQueueActivitiesPerSecond,
                    this.slotSupplier,
                    workerMetricsScope,
                    service.getServerCapabilities()),
                this.pollTaskExecutor,
                pollerOptions,
                workerMetricsScope);
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
    return poller
        .shutdown(shutdownManager, interruptTasks)
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
  public boolean isShutdown() {
    return poller.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return poller.isTerminated() && (pollTaskExecutor == null || pollTaskExecutor.isTerminated());
  }

  @Override
  public boolean isSuspended() {
    return poller.isSuspended();
  }

  @Override
  public WorkerLifecycleState getLifecycleState() {
    return poller.getLifecycleState();
  }

  public EagerActivityDispatcher getEagerActivityDispatcher() {
    return new EagerActivityDispatcherImpl();
  }

  private PollerOptions getPollerOptions(SingleWorkerOptions options) {
    PollerOptions pollerOptions = options.getPollerOptions();
    if (pollerOptions.getPollThreadNamePrefix() == null) {
      pollerOptions =
          PollerOptions.newBuilder(pollerOptions)
              .setPollThreadNamePrefix(
                  WorkerThreadsNameHelper.getActivityPollerThreadPrefix(namespace, taskQueue))
              .build();
    }
    return pollerOptions;
  }

  @Override
  public String toString() {
    return String.format(
        "ActivityWorker{identity=%s, namespace=%s, taskQueue=%s}",
        options.getIdentity(), namespace, taskQueue);
  }

  private class TaskHandlerImpl implements PollTaskExecutor.TaskHandler<ActivityTask> {

    final ActivityTaskHandler handler;

    private TaskHandlerImpl(ActivityTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(ActivityTask task) throws Exception {
      PollActivityTaskQueueResponseOrBuilder pollResponse = task.getResponse();

      slotSupplier.markSlotUsed(
          new ActivitySlotInfo(
              ActivityPollResponseToInfo.toActivityInfoImpl(
                  pollResponse, namespace, taskQueue, false),
              options.getIdentity(),
              options.getBuildId()),
          task.getPermit());

      Scope metricsScope =
          workerMetricsScope.tagged(
              ImmutableMap.of(
                  MetricsTag.ACTIVITY_TYPE,
                  pollResponse.getActivityType().getName(),
                  MetricsTag.WORKFLOW_TYPE,
                  pollResponse.getWorkflowType().getName()));

      MDC.put(LoggerTag.ACTIVITY_ID, pollResponse.getActivityId());
      MDC.put(LoggerTag.ACTIVITY_TYPE, pollResponse.getActivityType().getName());
      MDC.put(LoggerTag.WORKFLOW_ID, pollResponse.getWorkflowExecution().getWorkflowId());
      MDC.put(LoggerTag.WORKFLOW_TYPE, pollResponse.getWorkflowType().getName());
      MDC.put(LoggerTag.RUN_ID, pollResponse.getWorkflowExecution().getRunId());
      MDC.put(LoggerTag.ATTEMPT, Integer.toString(pollResponse.getAttempt()));

      ActivityTaskHandler.Result result = null;
      try {
        result = handleActivity(task, metricsScope);
      } finally {
        MDC.remove(LoggerTag.ACTIVITY_ID);
        MDC.remove(LoggerTag.ACTIVITY_TYPE);
        MDC.remove(LoggerTag.WORKFLOW_ID);
        MDC.remove(LoggerTag.WORKFLOW_TYPE);
        MDC.remove(LoggerTag.RUN_ID);
        MDC.remove(LoggerTag.ATTEMPT);
        if (
        // handleActivity throw an exception (not a normal scenario)
        result == null
            // completed synchronously or manual completion hasn't been requested
            || !result.isManualCompletion()) {
          task.getCompletionCallback().apply();
        }
      }

      if (result.getTaskFailed() != null && result.getTaskFailed().getFailure() instanceof Error) {
        // don't just swallow Errors, we need to propagate it to the top
        throw (Error) result.getTaskFailed().getFailure();
      }
    }

    private ActivityTaskHandler.Result handleActivity(ActivityTask task, Scope metricsScope) {
      PollActivityTaskQueueResponseOrBuilder pollResponse = task.getResponse();
      ByteString taskToken = pollResponse.getTaskToken();
      metricsScope
          .timer(MetricsType.ACTIVITY_SCHEDULE_TO_START_LATENCY)
          .record(
              ProtobufTimeUtils.toM3Duration(
                  pollResponse.getStartedTime(), pollResponse.getCurrentAttemptScheduledTime()));

      ActivityTaskHandler.Result result;

      Stopwatch sw = metricsScope.timer(MetricsType.ACTIVITY_EXEC_LATENCY).start();
      try {
        result = handler.handle(task, metricsScope, false);
      } catch (Throwable ex) {
        // handler.handle if expected to never throw an exception and return result
        // that can be used for a workflow callback if this method throws, it's a bug.
        log.error("[BUG] Code that expected to never throw an exception threw an exception", ex);
        throw ex;
      } finally {
        sw.stop();
      }

      try {
        sendReply(taskToken, result, metricsScope);
      } catch (Exception e) {
        logExceptionDuringResultReporting(e, pollResponse, result);
        // TODO this class doesn't report activity success and failure metrics now, instead it's
        //  located inside an activity handler. We should lift it up to this level,
        //  so we can increment a failure counter instead of success if send result failed.
        //  This will also align the behavior of ActivityWorker with WorkflowWorker.
        throw e;
      }

      if (result.getTaskCompleted() != null) {
        Duration e2eDuration =
            ProtobufTimeUtils.toM3DurationSinceNow(pollResponse.getScheduledTime());
        metricsScope.timer(MetricsType.ACTIVITY_SUCCEED_E2E_LATENCY).record(e2eDuration);
      }

      return result;
    }

    @Override
    public Throwable wrapFailure(ActivityTask t, Throwable failure) {
      PollActivityTaskQueueResponseOrBuilder response = t.getResponse();
      WorkflowExecution execution = response.getWorkflowExecution();
      return new RuntimeException(
          "Failure processing activity response. WorkflowId="
              + execution.getWorkflowId()
              + ", RunId="
              + execution.getRunId()
              + ", ActivityType="
              + response.getActivityType().getName()
              + ", ActivityId="
              + response.getActivityId(),
          failure);
    }

    /**
     * Executes a gRPC call with proper interrupted flag handling.
     * Activities that return with the 'interrupted' flag set, were unable to report their completion to the server.
     * We need to clear 'interrupted' flag to allow gRPC calls to succeed,then restore it after reporting completion,
     * to ensure gRPC calls succeed even when the thread has been interrupted.
     *
     * @param grpcCall the gRPC call to execute
     * @see <a href="https://github.com/temporalio/sdk-java/issues/731">GitHub Issue #731</a>
     */
    private void executeGrpcCallWithInterruptHandling(Runnable grpcCall) {
      // Check if the current thread is interrupted before making gRPC calls
      // If it is, we need to clear the flag to allow gRPC calls to succeed,then restore it after reporting.
      // This handles the case where an activity catches InterruptedException, restores the interrupted flag,
      // and continues to return a result.

      boolean wasInterrupted = Thread.interrupted(); // This clears the flag
      try {
        grpcCall.run();
      } finally {
        // Restore the interrupted flag if it was set
        if (wasInterrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    // TODO: Suppress warning until the SDK supports deployment
    @SuppressWarnings("deprecation")
    private void sendReply(
        ByteString taskToken, ActivityTaskHandler.Result response, Scope metricsScope) {
      RespondActivityTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        RespondActivityTaskCompletedRequest request =
            taskCompleted.toBuilder()
                .setTaskToken(taskToken)
                .setIdentity(options.getIdentity())
                .setNamespace(namespace)
                .setWorkerVersion(options.workerVersionStamp())
                .build();

        executeGrpcCallWithInterruptHandling(
            () ->
                grpcRetryer.retry(
                    () ->
                        service
                            .blockingStub()
                            .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                            .respondActivityTaskCompleted(request),
                    replyGrpcRetryerOptions));
      } else {
        Result.TaskFailedResult taskFailed = response.getTaskFailed();
        if (taskFailed != null) {
          RespondActivityTaskFailedRequest request =
              taskFailed.getTaskFailedRequest().toBuilder()
                  .setTaskToken(taskToken)
                  .setIdentity(options.getIdentity())
                  .setNamespace(namespace)
                  .setWorkerVersion(options.workerVersionStamp())
                  .build();

          executeGrpcCallWithInterruptHandling(
              () ->
                  grpcRetryer.retry(
                      () ->
                          service
                              .blockingStub()
                              .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                              .respondActivityTaskFailed(request),
                      replyGrpcRetryerOptions));
        } else {
          RespondActivityTaskCanceledRequest taskCanceled = response.getTaskCanceled();
          if (taskCanceled != null) {
            RespondActivityTaskCanceledRequest request =
                taskCanceled.toBuilder()
                    .setTaskToken(taskToken)
                    .setIdentity(options.getIdentity())
                    .setNamespace(namespace)
                    .setWorkerVersion(options.workerVersionStamp())
                    .build();

            executeGrpcCallWithInterruptHandling(
                () ->
                    grpcRetryer.retry(
                        () ->
                            service
                                .blockingStub()
                                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                                .respondActivityTaskCanceled(request),
                        replyGrpcRetryerOptions));
          }
        }
      }
      // Manual activity completion
    }

    private void logExceptionDuringResultReporting(
        Exception e,
        PollActivityTaskQueueResponseOrBuilder pollResponse,
        ActivityTaskHandler.Result result) {
      MDC.put(LoggerTag.ACTIVITY_ID, pollResponse.getActivityId());
      MDC.put(LoggerTag.ACTIVITY_TYPE, pollResponse.getActivityType().getName());
      MDC.put(LoggerTag.WORKFLOW_ID, pollResponse.getWorkflowExecution().getWorkflowId());
      MDC.put(LoggerTag.RUN_ID, pollResponse.getWorkflowExecution().getRunId());

      if (log.isDebugEnabled()) {
        log.debug(
            "Failure during reporting of activity result to the server. ActivityId = {}, ActivityType = {}, WorkflowId={}, WorkflowType={}, RunId={}, ActivityResult={}",
            pollResponse.getActivityId(),
            pollResponse.getActivityType().getName(),
            pollResponse.getWorkflowExecution().getWorkflowId(),
            pollResponse.getWorkflowType().getName(),
            pollResponse.getWorkflowExecution().getRunId(),
            result,
            e);
      } else {
        log.warn(
            "Failure during reporting of activity result to the server. ActivityId = {}, ActivityType = {}, WorkflowId={}, WorkflowType={}, RunId={}",
            pollResponse.getActivityId(),
            pollResponse.getActivityType().getName(),
            pollResponse.getWorkflowExecution().getWorkflowId(),
            pollResponse.getWorkflowType().getName(),
            pollResponse.getWorkflowExecution().getRunId(),
            e);
      }
    }
  }

  private final class EagerActivityDispatcherImpl implements EagerActivityDispatcher {
    @Override
    public Optional<SlotPermit> tryReserveActivitySlot(
        ScheduleActivityTaskCommandAttributesOrBuilder commandAttributes) {
      if (!WorkerLifecycleState.ACTIVE.equals(ActivityWorker.this.getLifecycleState())
          || !Objects.equals(
              commandAttributes.getTaskQueue().getName(), ActivityWorker.this.taskQueue)) {
        return Optional.empty();
      }
      return ActivityWorker.this.slotSupplier.tryReserveSlot(
          new SlotReservationData(
              ActivityWorker.this.taskQueue, options.getIdentity(), options.getBuildId()));
    }

    @Override
    public void releaseActivitySlotReservations(Iterable<SlotPermit> permits) {
      for (SlotPermit permit : permits) {
        ActivityWorker.this.slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), permit);
      }
    }

    @Override
    public void dispatchActivity(PollActivityTaskQueueResponse activity, SlotPermit permit) {
      ActivityWorker.this.pollTaskExecutor.process(
          new ActivityTask(
              activity,
              permit,
              () ->
                  ActivityWorker.this.slotSupplier.releaseSlot(
                      SlotReleaseReason.taskComplete(), permit)));
    }
  }
}
