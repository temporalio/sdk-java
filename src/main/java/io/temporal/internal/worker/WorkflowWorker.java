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

import static io.temporal.internal.common.GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS;
import static io.temporal.internal.metrics.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.internal.common.GrpcRetryer;
import io.temporal.internal.common.RpcRetryOptions;
import io.temporal.internal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import org.slf4j.MDC;

public final class WorkflowWorker
    implements SuspendableWorker, Functions.Proc1<PollWorkflowTaskQueueResponse> {

  private static final String POLL_THREAD_NAME_PREFIX = "Workflow Poller taskQueue=";

  private SuspendableWorker poller = new NoopSuspendableWorker();
  private PollTaskExecutor<PollWorkflowTaskQueueResponse> pollTaskExecutor;
  private final WorkflowTaskHandler handler;
  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private final String stickyTaskQueueName;
  private final WorkflowRunLockManager runLocks = new WorkflowRunLockManager();

  public WorkflowWorker(
      WorkflowServiceStubs service,
      String namespace,
      String taskQueue,
      SingleWorkerOptions options,
      WorkflowTaskHandler handler,
      String stickyTaskQueueName) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.handler = handler;
    this.stickyTaskQueueName = stickyTaskQueueName;

    PollerOptions pollerOptions = options.getPollerOptions();
    if (pollerOptions.getPollThreadNamePrefix() == null) {
      pollerOptions =
          PollerOptions.newBuilder(pollerOptions)
              .setPollThreadNamePrefix(
                  POLL_THREAD_NAME_PREFIX
                      + "\""
                      + taskQueue
                      + "\", namespace=\""
                      + namespace
                      + "\"")
              .build();
    }
    this.options = SingleWorkerOptions.newBuilder(options).setPollerOptions(pollerOptions).build();
  }

  @Override
  public void start() {
    if (handler.isAnyTypeSupported()) {
      pollTaskExecutor =
          new PollTaskExecutor<>(namespace, taskQueue, options, new TaskHandlerImpl(handler));
      poller =
          new Poller<>(
              options.getIdentity(),
              new WorkflowPollTask(
                  service, namespace, taskQueue, options.getMetricsScope(), options.getIdentity()),
              pollTaskExecutor,
              options.getPollerOptions(),
              options.getMetricsScope());
      poller.start();
      options.getMetricsScope().counter(MetricsType.WORKER_START_COUNTER).inc(1);
    }
  }

  @Override
  public boolean isStarted() {
    if (poller == null) {
      return false;
    }
    return poller.isStarted();
  }

  @Override
  public boolean isShutdown() {
    if (poller == null) {
      return true;
    }
    return poller.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    if (poller == null) {
      return true;
    }
    return poller.isTerminated();
  }

  public Optional<Payloads> queryWorkflowExecution(
      WorkflowExecution exec, String queryType, Optional<Payloads> args) throws Exception {
    GetWorkflowExecutionHistoryResponse historyResponse =
        WorkflowExecutionUtils.getHistoryPage(
            service, namespace, exec, ByteString.EMPTY, options.getMetricsScope());
    History history = historyResponse.getHistory();
    WorkflowExecutionHistory workflowExecutionHistory = new WorkflowExecutionHistory(history);
    return queryWorkflowExecution(
        queryType, args, workflowExecutionHistory, historyResponse.getNextPageToken());
  }

  public Optional<Payloads> queryWorkflowExecution(
      String jsonSerializedHistory, String queryType, Optional<Payloads> args) throws Exception {
    WorkflowExecutionHistory history = WorkflowExecutionHistory.fromJson(jsonSerializedHistory);
    return queryWorkflowExecution(queryType, args, history, ByteString.EMPTY);
  }

  public Optional<Payloads> queryWorkflowExecution(
      WorkflowExecutionHistory history, String queryType, Optional<Payloads> args)
      throws Exception {
    return queryWorkflowExecution(queryType, args, history, ByteString.EMPTY);
  }

  private Optional<Payloads> queryWorkflowExecution(
      String queryType,
      Optional<Payloads> args,
      WorkflowExecutionHistory history,
      ByteString nextPageToken)
      throws Exception {
    WorkflowQuery.Builder query = WorkflowQuery.newBuilder().setQueryType(queryType);
    if (args.isPresent()) {
      query.setQueryArgs(args.get());
    }
    PollWorkflowTaskQueueResponse.Builder task =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setWorkflowExecution(history.getWorkflowExecution())
            .setStartedEventId(Long.MAX_VALUE)
            .setPreviousStartedEventId(Long.MAX_VALUE)
            .setNextPageToken(nextPageToken)
            .setQuery(query);
    List<HistoryEvent> events = history.getEvents();
    HistoryEvent startedEvent = events.get(0);
    WorkflowExecutionStartedEventAttributes started =
        startedEvent.getWorkflowExecutionStartedEventAttributes();
    if (started == null) {
      throw new IllegalStateException(
          "First event of the history is not WorkflowExecutionStarted: " + startedEvent);
    }
    WorkflowType workflowType = started.getWorkflowType();
    task.setWorkflowType(workflowType);
    task.setHistory(History.newBuilder().addAllEvents(events));
    WorkflowTaskHandler.Result result = handler.handleWorkflowTask(task.build());
    if (result.getQueryCompleted() != null) {
      RespondQueryTaskCompletedRequest r = result.getQueryCompleted();
      if (!r.getErrorMessage().isEmpty()) {
        throw new RuntimeException(
            "query failure for "
                + history.getWorkflowExecution()
                + ", queryType="
                + queryType
                + ", args="
                + args
                + ", error="
                + r.getErrorMessage());
      }
      if (r.hasQueryResult()) {
        return Optional.of(r.getQueryResult());
      } else {
        return Optional.empty();
      }
    }
    throw new RuntimeException("Query returned wrong response: " + result);
  }

  @Override
  public void shutdown() {
    if (poller == null) {
      return;
    }
    poller.shutdown();
  }

  @Override
  public void shutdownNow() {
    if (poller == null) {
      return;
    }
    poller.shutdownNow();
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    if (poller == null || !poller.isStarted()) {
      return;
    }

    poller.awaitTermination(timeout, unit);
  }

  @Override
  public void suspendPolling() {
    if (poller == null) {
      return;
    }
    poller.suspendPolling();
  }

  @Override
  public void resumePolling() {
    if (poller == null) {
      return;
    }
    poller.resumePolling();
  }

  @Override
  public boolean isSuspended() {
    if (poller == null) {
      return false;
    }
    return poller.isSuspended();
  }

  @Override
  public void apply(PollWorkflowTaskQueueResponse pollWorkflowTaskQueueResponse) {
    pollTaskExecutor.process(pollWorkflowTaskQueueResponse);
  }

  private class TaskHandlerImpl
      implements PollTaskExecutor.TaskHandler<PollWorkflowTaskQueueResponse> {

    final WorkflowTaskHandler handler;

    private TaskHandlerImpl(WorkflowTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(PollWorkflowTaskQueueResponse task) throws Exception {
      Scope metricsScope =
          options
              .getMetricsScope()
              .tagged(ImmutableMap.of(MetricsTag.WORKFLOW_TYPE, task.getWorkflowType().getName()));

      MDC.put(LoggerTag.WORKFLOW_ID, task.getWorkflowExecution().getWorkflowId());
      MDC.put(LoggerTag.WORKFLOW_TYPE, task.getWorkflowType().getName());
      MDC.put(LoggerTag.RUN_ID, task.getWorkflowExecution().getRunId());

      Lock runLock = null;
      if (!Strings.isNullOrEmpty(stickyTaskQueueName)) {
        runLock = runLocks.getLockForLocking(task.getWorkflowExecution().getRunId());
        runLock.lock();
      }

      Stopwatch swTotal =
          metricsScope.timer(MetricsType.WORKFLOW_TASK_EXECUTION_TOTAL_LATENCY).start();
      try {
        Optional<PollWorkflowTaskQueueResponse> nextTask = Optional.of(task);
        do {
          Stopwatch sw = metricsScope.timer(MetricsType.WORKFLOW_TASK_EXECUTION_LATENCY).start();
          WorkflowTaskHandler.Result response;
          try {
            response = handler.handleWorkflowTask(nextTask.get());
          } finally {
            sw.stop();
          }
          nextTask = sendReply(service, metricsScope, task.getTaskToken(), response);
          if (nextTask.isPresent()) {
            metricsScope.counter(MetricsType.WORKFLOW_TASK_HEARTBEAT_COUNTER).inc(1);
          }
        } while (nextTask.isPresent());
      } finally {
        swTotal.stop();
        MDC.remove(LoggerTag.WORKFLOW_ID);
        MDC.remove(LoggerTag.WORKFLOW_TYPE);
        MDC.remove(LoggerTag.RUN_ID);

        if (runLock != null) {
          runLocks.unlock(task.getWorkflowExecution().getRunId());
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
              + execution.getRunId(),
          failure);
    }

    private Optional<PollWorkflowTaskQueueResponse> sendReply(
        WorkflowServiceStubs service,
        Scope metricsScope,
        ByteString taskToken,
        WorkflowTaskHandler.Result response) {
      RpcRetryOptions ro = response.getRequestRetryOptions();
      RespondWorkflowTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        ro = RpcRetryOptions.newBuilder().setRetryOptions(ro).validateBuildWithDefaults();

        RespondWorkflowTaskCompletedRequest request =
            taskCompleted
                .toBuilder()
                .setIdentity(options.getIdentity())
                .setTaskToken(taskToken)
                .build();
        Map<String, String> tags =
            new ImmutableMap.Builder<String, String>(4)
                .put(MetricsTag.WORKFLOW_TYPE, response.getWorkflowType())
                .build();
        AtomicReference<RespondWorkflowTaskCompletedResponse> nextTask = new AtomicReference<>();
        GrpcRetryer.retry(
            ro,
            () ->
                nextTask.set(
                    service
                        .blockingStub()
                        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope.tagged(tags))
                        .respondWorkflowTaskCompleted(request)));
        if (nextTask.get().hasWorkflowTask()) {
          return Optional.of(nextTask.get().getWorkflowTask());
        }
      } else {
        RespondWorkflowTaskFailedRequest taskFailed = response.getTaskFailed();
        if (taskFailed != null) {
          ro =
              RpcRetryOptions.newBuilder(DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS)
                  .setRetryOptions(ro)
                  .validateBuildWithDefaults();

          RespondWorkflowTaskFailedRequest request =
              taskFailed
                  .toBuilder()
                  .setIdentity(options.getIdentity())
                  .setTaskToken(taskToken)
                  .build();
          GrpcRetryer.retry(
              ro,
              () ->
                  service
                      .blockingStub()
                      .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, options.getMetricsScope())
                      .respondWorkflowTaskFailed(request));
        } else {
          RespondQueryTaskCompletedRequest queryCompleted = response.getQueryCompleted();
          if (queryCompleted != null) {
            queryCompleted = queryCompleted.toBuilder().setTaskToken(taskToken).build();
            // Do not retry query response.
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, options.getMetricsScope())
                .respondQueryTaskCompleted(queryCompleted);
          }
        }
      }
      return Optional.empty();
    }
  }
}
