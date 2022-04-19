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

package io.temporal.internal.worker;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.workflow.Functions;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class WorkflowWorker
    implements SuspendableWorker, Functions.Proc1<PollWorkflowTaskQueueResponse> {
  private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);

  private final WorkflowRunLockManager runLocks = new WorkflowRunLockManager();

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private final WorkflowTaskHandler handler;
  private final String stickyTaskQueueName;
  private final PollerOptions pollerOptions;
  private final Scope workerMetricsScope;

  @Nonnull private SuspendableWorker poller = new NoopSuspendableWorker();
  private PollTaskExecutor<PollWorkflowTaskQueueResponse> pollTaskExecutor;

  public WorkflowWorker(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nullable String stickyTaskQueueName,
      @Nonnull SingleWorkerOptions options,
      @Nonnull WorkflowTaskHandler handler) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.options = Objects.requireNonNull(options);
    this.handler = Objects.requireNonNull(handler);
    this.stickyTaskQueueName = stickyTaskQueueName;
    this.pollerOptions = getPollerOptions(options);
    this.workerMetricsScope =
        MetricsTag.tagged(options.getMetricsScope(), WorkerMetricsTag.WorkerType.WORKFLOW_WORKER);
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
      poller =
          new Poller<>(
              options.getIdentity(),
              new WorkflowPollTask(
                  service,
                  namespace,
                  taskQueue,
                  options.getIdentity(),
                  options.getBinaryChecksum(),
                  workerMetricsScope),
              pollTaskExecutor,
              pollerOptions,
              workerMetricsScope);
      poller.start();
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
    return poller.shutdown(shutdownManager, interruptTasks);
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    poller.awaitTermination(timeout, unit);
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
  public void apply(PollWorkflowTaskQueueResponse pollWorkflowTaskQueueResponse) {
    pollTaskExecutor.process(pollWorkflowTaskQueueResponse);
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

  private class TaskHandlerImpl
      implements PollTaskExecutor.TaskHandler<PollWorkflowTaskQueueResponse> {

    final WorkflowTaskHandler handler;

    private TaskHandlerImpl(WorkflowTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(PollWorkflowTaskQueueResponse task) throws Exception {
      Scope workflowTypeMetricsScope =
          workerMetricsScope.tagged(
              ImmutableMap.of(MetricsTag.WORKFLOW_TYPE, task.getWorkflowType().getName()));

      String runId = task.getWorkflowExecution().getRunId();

      MDC.put(LoggerTag.WORKFLOW_ID, task.getWorkflowExecution().getWorkflowId());
      MDC.put(LoggerTag.WORKFLOW_TYPE, task.getWorkflowType().getName());
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
          workflowTypeMetricsScope.timer(MetricsType.WORKFLOW_TASK_EXECUTION_TOTAL_LATENCY).start();
      try {
        Optional<PollWorkflowTaskQueueResponse> nextTask = Optional.of(task);
        do {
          PollWorkflowTaskQueueResponse currentTask = nextTask.get();
          WorkflowTaskHandler.Result response = handleTask(currentTask, workflowTypeMetricsScope);
          nextTask = sendReply(service, workflowTypeMetricsScope, currentTask, response);

          // this should be after sendReply, otherwise we may log
          // WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER twice if sendReply throws
          if (response.getTaskFailed() != null) {
            // we don't trigger the counter in case of the legacy query
            // (which never has taskFailed set)
            workflowTypeMetricsScope
                .counter(MetricsType.WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER)
                .inc(1);
          }
          if (nextTask.isPresent()) {
            workflowTypeMetricsScope.counter(MetricsType.WORKFLOW_TASK_HEARTBEAT_COUNTER).inc(1);
          }
        } while (nextTask.isPresent());
      } finally {
        swTotal.stop();
        MDC.remove(LoggerTag.WORKFLOW_ID);
        MDC.remove(LoggerTag.WORKFLOW_TYPE);
        MDC.remove(LoggerTag.RUN_ID);

        if (locked) {
          runLocks.unlock(runId);
        }
      }
    }

    @Override
    public Throwable wrapFailure(PollWorkflowTaskQueueResponse task, Throwable failure) {
      WorkflowExecution execution = task.getWorkflowExecution();
      return new RuntimeException(
          "Failure processing workflow task. WorkflowId="
              + execution.getWorkflowId()
              + ", RunId="
              + execution.getRunId()
              + ", Attempt="
              + task.getAttempt(),
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
        WorkflowServiceStubs service,
        Scope workflowTypeMetricsScope,
        PollWorkflowTaskQueueResponse task,
        WorkflowTaskHandler.Result response) {
      try {
        ByteString taskToken = task.getTaskToken();
        RpcRetryOptions retryOptions = response.getRequestRetryOptions();
        RespondWorkflowTaskCompletedRequest taskCompleted = response.getTaskCompleted();
        if (taskCompleted != null) {
          retryOptions = RpcRetryOptions.newBuilder().buildWithDefaultsFrom(retryOptions);

          RespondWorkflowTaskCompletedRequest request =
              taskCompleted
                  .toBuilder()
                  .setIdentity(options.getIdentity())
                  .setNamespace(namespace)
                  .setBinaryChecksum(options.getBinaryChecksum())
                  .setTaskToken(taskToken)
                  .build();
          AtomicReference<RespondWorkflowTaskCompletedResponse> nextTask = new AtomicReference<>();
          GrpcRetryer.retry(
              retryOptions,
              () ->
                  nextTask.set(
                      service
                          .blockingStub()
                          .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, workflowTypeMetricsScope)
                          .respondWorkflowTaskCompleted(request)));
          if (nextTask.get().hasWorkflowTask()) {
            return Optional.of(nextTask.get().getWorkflowTask());
          }
        } else {
          RespondWorkflowTaskFailedRequest taskFailed = response.getTaskFailed();
          if (taskFailed != null) {
            retryOptions = RpcRetryOptions.newBuilder().buildWithDefaultsFrom(retryOptions);

            RespondWorkflowTaskFailedRequest request =
                taskFailed
                    .toBuilder()
                    .setIdentity(options.getIdentity())
                    .setNamespace(namespace)
                    .setTaskToken(taskToken)
                    .build();
            GrpcRetryer.retry(
                retryOptions,
                () ->
                    service
                        .blockingStub()
                        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, workflowTypeMetricsScope)
                        .respondWorkflowTaskFailed(request));
          } else {
            RespondQueryTaskCompletedRequest queryCompleted = response.getQueryCompleted();
            if (queryCompleted != null) {
              queryCompleted =
                  queryCompleted
                      .toBuilder()
                      .setTaskToken(taskToken)
                      .setNamespace(namespace)
                      .build();
              // Do not retry query response.
              service
                  .blockingStub()
                  .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, workflowTypeMetricsScope)
                  .respondQueryTaskCompleted(queryCompleted);
            }
          }
        }
        return Optional.empty();
      } catch (Exception e) {
        WorkflowExecution execution = task.getWorkflowExecution();
        log.warn(
            "Workflow task failure during replying to the server. startedEventId={}, WorkflowId={}, RunId={}. If seen continuously the workflow might be stuck.",
            task.getStartedEventId(),
            execution.getWorkflowId(),
            execution.getRunId(),
            e);
        workflowTypeMetricsScope
            .counter(MetricsType.WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER)
            .inc(1);
        throw e;
      }
    }
  }
}
