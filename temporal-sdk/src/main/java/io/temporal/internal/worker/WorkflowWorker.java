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

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.replay.WorkflowExecutorCache;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

final class WorkflowWorker implements SuspendableWorker {
  private static final double STICKY_TO_NORMAL_RATIO = 5;

  private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);

  private final WorkflowRunLockManager runLocks = new WorkflowRunLockManager();

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private final WorkflowExecutorCache cache;
  private final WorkflowTaskHandler handler;
  private final String stickyTaskQueueName;
  private final PollerOptions pollerOptions;
  private final PollerOptions stickyPollerOptions;
  private final Scope workerMetricsScope;
  private final GrpcRetryer grpcRetryer;

  @Nonnull private SuspendableWorker poller = new NoopSuspendableWorker();

  @Nullable private SuspendableWorker stickyPoller = new NoopSuspendableWorker();

  private PollTaskExecutor<WorkflowTask> pollTaskExecutor;

  public WorkflowWorker(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nullable String stickyTaskQueueName,
      @Nonnull SingleWorkerOptions options,
      @Nonnull WorkflowExecutorCache cache,
      @Nonnull WorkflowTaskHandler handler) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.options = Objects.requireNonNull(options);
    this.stickyTaskQueueName = stickyTaskQueueName;
    this.pollerOptions = getPollerOptions(options);
    this.stickyPollerOptions = stickyTaskQueueName != null ? getStickyPollerOptions(options) : null;
    this.workerMetricsScope =
        MetricsTag.tagged(options.getMetricsScope(), WorkerMetricsTag.WorkerType.WORKFLOW_WORKER);
    this.cache = Objects.requireNonNull(cache);
    this.handler = Objects.requireNonNull(handler);
    this.grpcRetryer = new GrpcRetryer(service.getServerCapabilities());
  }

  @Override
  public void start() {
    if (handler.isAnyTypeSupported()) {
      pollTaskExecutor =
          new PollTaskExecutor<>(
              namespace,
              taskQueue,
              options.getIdentity(),
              new TaskHandlerImpl(handler),
              pollerOptions,
              options.getTaskExecutorThreadPoolSize(),
              workerMetricsScope);
      Semaphore workflowTaskExecutorSemaphore =
          new Semaphore(options.getTaskExecutorThreadPoolSize());
      poller =
          new Poller<>(
              options.getIdentity(),
              new WorkflowPollTask(
                  service,
                  namespace,
                  taskQueue,
                  TaskQueueKind.TASK_QUEUE_KIND_NORMAL,
                  options.getIdentity(),
                  options.getBinaryChecksum(),
                  workflowTaskExecutorSemaphore,
                  workerMetricsScope),
              pollTaskExecutor,
              pollerOptions,
              workerMetricsScope);
      poller.start();

      if (stickyPollerOptions != null) {
        Scope stickyScope =
            workerMetricsScope.tagged(
                new ImmutableMap.Builder<String, String>(1)
                    .put(MetricsTag.TASK_QUEUE, String.format("%s:%s", taskQueue, "sticky"))
                    .build());
        stickyPoller =
            new Poller<>(
                options.getIdentity(),
                new WorkflowPollTask(
                    service,
                    namespace,
                    stickyTaskQueueName,
                    TaskQueueKind.TASK_QUEUE_KIND_STICKY,
                    options.getIdentity(),
                    options.getBinaryChecksum(),
                    workflowTaskExecutorSemaphore,
                    stickyScope),
                pollTaskExecutor,
                stickyPollerOptions,
                stickyScope);
        stickyPoller.start();
      }

      workerMetricsScope.counter(MetricsType.WORKER_START_COUNTER).inc(1);
    }
  }

  @Override
  public boolean isStarted() {
    return poller.isStarted();
  }

  @Override
  public boolean isShutdown() {
    return poller.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return poller.isTerminated();
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    CompletableFuture<Void> shutdownFuture = poller.shutdown(shutdownManager, interruptTasks);
    if (stickyPoller != null) {
      shutdownFuture =
          CompletableFuture.allOf(
              shutdownFuture, stickyPoller.shutdown(shutdownManager, interruptTasks));
    }
    return shutdownFuture;
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = ShutdownManager.awaitTermination(poller, unit.toMillis(timeout));
    if (stickyPoller != null) {
      ShutdownManager.awaitTermination(stickyPoller, timeoutMillis);
    }
  }

  @Override
  public void suspendPolling() {
    poller.suspendPolling();
    if (stickyPoller != null) {
      stickyPoller.suspendPolling();
    }
  }

  @Override
  public void resumePolling() {
    poller.resumePolling();
    if (stickyPoller != null) {
      stickyPoller.resumePolling();
    }
  }

  @Override
  public boolean isSuspended() {
    return poller.isSuspended();
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

  private PollerOptions getStickyPollerOptions(SingleWorkerOptions options) {
    PollerOptions stickyPollerOptions = options.getPollerOptions();
    stickyPollerOptions =
        PollerOptions.newBuilder(stickyPollerOptions)
            .setPollThreadNamePrefix(
                WorkerThreadsNameHelper.getStickyQueueWorkflowPollerThreadPrefix(
                    namespace, stickyTaskQueueName))
            .setPollThreadCount(
                (int) (stickyPollerOptions.getPollThreadCount() * STICKY_TO_NORMAL_RATIO))
            .build();

    return stickyPollerOptions;
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
        // TODO 1: "1 second" timeout looks potentially too strict, especially if we are talking
        //   about workflow tasks with local activities.
        //   This could lead to unexpected fail of queries and reset of sticky queue.
        //   Should it be connected to a workflow task timeout / query timeout?
        // TODO 2: Does "consider increasing workflow task timeout" advice in this exception makes
        //   any sense?
        //   This MAYBE makes sense only if a previous workflow task timed out, it's still in
        //   progress on the worker and the next workflow task got picked up by the same exact
        //   worker from the general non-sticky task queue.
        //   Even in this case, this advice looks misleading, something else is going on
        //   (like an extreme network latency).
        locked = runLocks.tryLock(runId, 1, TimeUnit.SECONDS);

        if (!locked) {
          throw new UnableToAcquireLockException(
              "Workflow lock for the run id hasn't been released by one of previous execution attempts, "
                  + "consider increasing workflow task timeout.");
        }
      }

      Stopwatch swTotal =
          workflowTypeScope.timer(MetricsType.WORKFLOW_TASK_EXECUTION_TOTAL_LATENCY).start();
      try {
        Optional<PollWorkflowTaskQueueResponse> nextWFTResponse = Optional.of(workflowTaskResponse);
        do {
          PollWorkflowTaskQueueResponse currentTask = nextWFTResponse.get();
          WorkflowTaskHandler.Result result = handleTask(currentTask, workflowTypeScope);
          try {
            nextWFTResponse =
                sendReply(currentTask.getTaskToken(), service, workflowTypeScope, result);
          } catch (Exception e) {
            logExceptionDuringResultReporting(e, currentTask, result);
            workflowTypeScope.counter(MetricsType.WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER).inc(1);
            // if we failed to report the workflow task completion back to the server,
            // our cached version of the workflow may be more advanced than the server is aware of.
            // We should discard this execution and perform a clean replay based on what server
            // knows next time.
            cache.invalidate(
                workflowExecution, workflowTypeScope, "Failed result reporting to the server", e);
            throw e;
          }

          // this should be after sendReply, otherwise we may log
          // WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER twice if sendReply throws
          if (result.getTaskFailed() != null) {
            // we don't trigger the counter in case of the legacy query
            // (which never has taskFailed set)
            workflowTypeScope.counter(MetricsType.WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER).inc(1);
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

    private Optional<PollWorkflowTaskQueueResponse> sendReply(
        ByteString taskToken,
        WorkflowServiceStubs service,
        Scope workflowTypeMetricsScope,
        WorkflowTaskHandler.Result response) {
      RpcRetryOptions retryOptions = response.getRequestRetryOptions();
      RespondWorkflowTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        GrpcRetryer.GrpcRetryerOptions grpcRetryOptions =
            new GrpcRetryer.GrpcRetryerOptions(
                RpcRetryOptions.newBuilder().buildWithDefaultsFrom(retryOptions), null);

        RespondWorkflowTaskCompletedRequest request =
            taskCompleted.toBuilder()
                .setIdentity(options.getIdentity())
                .setNamespace(namespace)
                .setBinaryChecksum(options.getBinaryChecksum())
                .setTaskToken(taskToken)
                .build();
        AtomicReference<RespondWorkflowTaskCompletedResponse> nextTask = new AtomicReference<>();
        grpcRetryer.retry(
            () ->
                nextTask.set(
                    service
                        .blockingStub()
                        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, workflowTypeMetricsScope)
                        .respondWorkflowTaskCompleted(request)),
            grpcRetryOptions);
        if (nextTask.get().hasWorkflowTask()) {
          return Optional.of(nextTask.get().getWorkflowTask());
        }
      } else {
        RespondWorkflowTaskFailedRequest taskFailed = response.getTaskFailed();
        if (taskFailed != null) {
          GrpcRetryer.GrpcRetryerOptions grpcRetryOptions =
              new GrpcRetryer.GrpcRetryerOptions(
                  RpcRetryOptions.newBuilder().buildWithDefaultsFrom(retryOptions), null);

          RespondWorkflowTaskFailedRequest request =
              taskFailed.toBuilder()
                  .setIdentity(options.getIdentity())
                  .setNamespace(namespace)
                  .setTaskToken(taskToken)
                  .build();
          grpcRetryer.retry(
              () ->
                  service
                      .blockingStub()
                      .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, workflowTypeMetricsScope)
                      .respondWorkflowTaskFailed(request),
              grpcRetryOptions);
        } else {
          RespondQueryTaskCompletedRequest queryCompleted = response.getQueryCompleted();
          if (queryCompleted != null) {
            queryCompleted =
                queryCompleted.toBuilder().setTaskToken(taskToken).setNamespace(namespace).build();
            // Do not retry query response.
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, workflowTypeMetricsScope)
                .respondQueryTaskCompleted(queryCompleted);
          }
        }
      }
      return Optional.empty();
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
