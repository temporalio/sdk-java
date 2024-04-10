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

package io.temporal.internal.worker;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;
import static io.temporal.serviceclient.MetricsTag.TASK_FAILURE_TYPE;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.worker.WorkflowTaskDispatchHandle;
import io.temporal.worker.tuning.WorkflowSlotInfo;
import java.util.Objects;
import java.util.Optional;
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

  private final WorkflowRunLockManager runLocks;

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private final WorkflowExecutorCache cache;
  private final WorkflowTaskHandler handler;
  private final String stickyTaskQueueName;
  private final PollerOptions pollerOptions;
  private final Scope workerMetricsScope;
  private final GrpcRetryer grpcRetryer;
  private final EagerActivityDispatcher eagerActivityDispatcher;
  private final TrackingSlotSupplier<WorkflowSlotInfo> slotSupplier;

  private PollTaskExecutor<WorkflowTask> pollTaskExecutor;

  // TODO this ideally should be volatile or final (and NoopWorker should go away)
  //  Currently the implementation looks safe without volatile, but it's brittle.
  @Nonnull private SuspendableWorker poller = new NoopWorker();

  private StickyQueueBalancer stickyQueueBalancer;

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
      @Nonnull TrackingSlotSupplier<WorkflowSlotInfo> slotSupplier) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.options = Objects.requireNonNull(options);
    this.stickyTaskQueueName = stickyTaskQueueName;
    this.pollerOptions = getPollerOptions(options);
    this.workerMetricsScope =
        MetricsTag.tagged(options.getMetricsScope(), WorkerMetricsTag.WorkerType.WORKFLOW_WORKER);
    slotSupplier.setMetricsScope(workerMetricsScope);
    this.runLocks = Objects.requireNonNull(runLocks);
    this.cache = Objects.requireNonNull(cache);
    this.handler = Objects.requireNonNull(handler);
    this.grpcRetryer = new GrpcRetryer(service.getServerCapabilities());
    this.eagerActivityDispatcher = eagerActivityDispatcher;
    this.slotSupplier = slotSupplier;
  }

  @Override
  public boolean start() {
    if (handler.isAnyTypeSupported()) {
      pollTaskExecutor =
          new PollTaskExecutor<>(
              namespace,
              taskQueue,
              options.getIdentity(),
              new TaskHandlerImpl(handler),
              pollerOptions,
              this.slotSupplier.maximumSlots(),
              true);
      stickyQueueBalancer =
          new StickyQueueBalancer(
              options.getPollerOptions().getPollThreadCount(), stickyTaskQueueName != null);

      poller =
          new Poller<>(
              options.getIdentity(),
              new WorkflowPollTask(
                  service,
                  namespace,
                  taskQueue,
                  stickyTaskQueueName,
                  options.getIdentity(),
                  options.getBuildId(),
                  options.isUsingBuildIdForVersioning(),
                  slotSupplier,
                  stickyQueueBalancer,
                  workerMetricsScope,
                  service.getServerCapabilities()),
              pollTaskExecutor,
              pollerOptions,
              workerMetricsScope);
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
            && stickyQueueBalancer != null;

    return CompletableFuture.completedFuture(null)
        .thenCompose(
            ignore ->
                stickyQueueBalancerDrainEnabled
                    ? shutdownManager.waitForStickyQueueBalancer(
                        stickyQueueBalancer, options.getDrainStickyTaskQueueTimeout())
                    : CompletableFuture.completedFuture(null))
        .thenCompose(ignore -> poller.shutdown(shutdownManager, interruptTasks))
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
                    slotPermit))
        .orElse(null);
  }

  @Override
  public String toString() {
    return String.format(
        "WorkflowWorker{identity=%s, namespace=%s, taskQueue=%s}",
        options.getIdentity(), namespace, taskQueue);
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
          WorkflowTaskHandler.Result result = handleTask(currentTask, workflowTypeScope);
          try {
            RespondWorkflowTaskCompletedRequest taskCompleted = result.getTaskCompleted();
            RespondWorkflowTaskFailedRequest taskFailed = result.getTaskFailed();
            RespondQueryTaskCompletedRequest queryCompleted = result.getQueryCompleted();

            if (taskCompleted != null) {
              RespondWorkflowTaskCompletedRequest.Builder requestBuilder =
                  taskCompleted.toBuilder();
              try (EagerActivitySlotsReservation activitySlotsReservation =
                  new EagerActivitySlotsReservation(eagerActivityDispatcher)) {
                activitySlotsReservation.applyToRequest(requestBuilder);
                RespondWorkflowTaskCompletedResponse response =
                    sendTaskCompleted(
                        currentTask.getTaskToken(),
                        requestBuilder,
                        result.getRequestRetryOptions(),
                        workflowTypeScope);
                // If we were processing a speculative WFT the server may instruct us that the task
                // was dropped by resting out event ID.
                long resetEventId = response.getResetHistoryEventId();
                if (resetEventId != 0) {
                  result.getEventIdSetHandle().apply(resetEventId);
                }
                nextWFTResponse =
                    response.hasWorkflowTask()
                        ? Optional.of(response.getWorkflowTask())
                        : Optional.empty();
                // TODO we don't have to do this under the runId lock
                activitySlotsReservation.handleResponse(response);
              }
            } else if (taskFailed != null) {
              sendTaskFailed(
                  currentTask.getTaskToken(),
                  taskFailed.toBuilder(),
                  result.getRequestRetryOptions(),
                  workflowTypeScope);
            } else if (queryCompleted != null) {
              sendDirectQueryCompletedResponse(
                  currentTask.getTaskToken(), queryCompleted.toBuilder(), workflowTypeScope);
            }
          } catch (Exception e) {
            logExceptionDuringResultReporting(e, currentTask, result);
            // if we failed to report the workflow task completion back to the server,
            // our cached version of the workflow may be more advanced than the server is aware of.
            // We should discard this execution and perform a clean replay based on what server
            // knows next time.
            cache.invalidate(
                workflowExecution, workflowTypeScope, "Failed result reporting to the server", e);
            throw e;
          }

          if (result.getTaskFailed() != null) {
            Scope workflowTaskFailureScope = workflowTypeScope;
            if (result
                .getTaskFailed()
                .getCause()
                .equals(
                    WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_NON_DETERMINISTIC_ERROR)) {
              workflowTaskFailureScope =
                  workflowTaskFailureScope.tagged(
                      ImmutableMap.of(TASK_FAILURE_TYPE, "NonDeterminismError"));
            } else {
              workflowTaskFailureScope =
                  workflowTaskFailureScope.tagged(
                      ImmutableMap.of(TASK_FAILURE_TYPE, "WorkflowError"));
            }
            // we don't trigger the counter in case of the legacy query
            // (which never has taskFailed set)
            workflowTaskFailureScope
                .counter(MetricsType.WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER)
                .inc(1);
          }
          if (nextWFTResponse.isPresent()) {
            workflowTypeScope.counter(MetricsType.WORKFLOW_TASK_HEARTBEAT_COUNTER).inc(1);
          }
        } while (nextWFTResponse.isPresent());
      } finally {
        swTotal.stop();
        MDC.remove(LoggerTag.WORKFLOW_ID);
        MDC.remove(LoggerTag.WORKFLOW_TYPE);
        MDC.remove(LoggerTag.RUN_ID);

        task.getCompletionCallback().apply();

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
        // more detailed logging that we can do here is already done inside `handler`
        workflowTypeMetricsScope
            .counter(MetricsType.WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER)
            .inc(1);
        workflowTypeMetricsScope.counter(MetricsType.WORKFLOW_TASK_NO_COMPLETION_COUNTER).inc(1);
        throw e;
      } finally {
        sw.stop();
      }
    }

    private RespondWorkflowTaskCompletedResponse sendTaskCompleted(
        ByteString taskToken,
        RespondWorkflowTaskCompletedRequest.Builder taskCompleted,
        RpcRetryOptions retryOptions,
        Scope workflowTypeMetricsScope) {
      GrpcRetryer.GrpcRetryerOptions grpcRetryOptions =
          new GrpcRetryer.GrpcRetryerOptions(
              RpcRetryOptions.newBuilder().buildWithDefaultsFrom(retryOptions), null);

      taskCompleted
          .setIdentity(options.getIdentity())
          .setNamespace(namespace)
          .setTaskToken(taskToken);
      if (service.getServerCapabilities().get().getBuildIdBasedVersioning()) {
        taskCompleted.setWorkerVersionStamp(options.workerVersionStamp());
      } else {
        taskCompleted.setBinaryChecksum(options.getBuildId());
      }

      return grpcRetryer.retryWithResult(
          () ->
              service
                  .blockingStub()
                  .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, workflowTypeMetricsScope)
                  .respondWorkflowTaskCompleted(taskCompleted.build()),
          grpcRetryOptions);
    }

    private void sendTaskFailed(
        ByteString taskToken,
        RespondWorkflowTaskFailedRequest.Builder taskFailed,
        RpcRetryOptions retryOptions,
        Scope workflowTypeMetricsScope) {
      GrpcRetryer.GrpcRetryerOptions grpcRetryOptions =
          new GrpcRetryer.GrpcRetryerOptions(
              RpcRetryOptions.newBuilder().buildWithDefaultsFrom(retryOptions), null);

      taskFailed.setIdentity(options.getIdentity()).setNamespace(namespace).setTaskToken(taskToken);

      if (service.getServerCapabilities().get().getBuildIdBasedVersioning()) {
        taskFailed.setWorkerVersion(options.workerVersionStamp());
      }

      grpcRetryer.retry(
          () ->
              service
                  .blockingStub()
                  .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, workflowTypeMetricsScope)
                  .respondWorkflowTaskFailed(taskFailed.build()),
          grpcRetryOptions);
    }

    private void sendDirectQueryCompletedResponse(
        ByteString taskToken,
        RespondQueryTaskCompletedRequest.Builder queryCompleted,
        Scope workflowTypeMetricsScope) {
      queryCompleted.setTaskToken(taskToken).setNamespace(namespace);
      // Do not retry query response
      service
          .blockingStub()
          .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, workflowTypeMetricsScope)
          .respondQueryTaskCompleted(queryCompleted.build());
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
  }
}
