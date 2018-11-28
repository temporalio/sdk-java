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

package com.uber.cadence.internal.worker;

import com.uber.cadence.*;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.common.WorkflowExecutionHistory;
import com.uber.cadence.internal.common.Retryer;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.logging.LoggerTag;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.m3.tally.Stopwatch;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class WorkflowWorker
    implements SuspendableWorker, Consumer<PollForDecisionTaskResponse> {

  private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);

  private static final String POLL_THREAD_NAME_PREFIX = "Workflow Poller taskList=";

  private Poller poller;
  private final PollTaskExecutor<PollForDecisionTaskResponse> pollTaskExecutor;
  private final DecisionTaskHandler handler;
  private final IWorkflowService service;
  private final String domain;
  private final String taskList;
  private final SingleWorkerOptions options;

  public WorkflowWorker(
      IWorkflowService service,
      String domain,
      String taskList,
      SingleWorkerOptions options,
      DecisionTaskHandler handler) {
    Objects.requireNonNull(service);
    Objects.requireNonNull(domain);
    Objects.requireNonNull(taskList);
    this.service = service;
    this.domain = domain;
    this.taskList = taskList;
    this.options = options;
    this.handler = handler;
    pollTaskExecutor =
        new PollTaskExecutor<>(domain, taskList, options, new TaskHandlerImpl(handler));
  }

  @Override
  public void start() {
    if (handler.isAnyTypeSupported()) {
      PollerOptions pollerOptions = options.getPollerOptions();
      if (pollerOptions.getPollThreadNamePrefix() == null) {
        pollerOptions =
            new PollerOptions.Builder(pollerOptions)
                .setPollThreadNamePrefix(
                    POLL_THREAD_NAME_PREFIX
                        + "\""
                        + taskList
                        + "\", domain=\""
                        + domain
                        + "\", type=\"workflow\"")
                .build();
      }
      SingleWorkerOptions workerOptions =
          new SingleWorkerOptions.Builder(options).setPollerOptions(pollerOptions).build();

      poller =
          new Poller<>(
              options.getIdentity(),
              new WorkflowPollTask(
                  service, domain, taskList, options.getMetricsScope(), options.getIdentity()),
              pollTaskExecutor,
              pollerOptions,
              workerOptions.getMetricsScope());
      poller.start();
      options.getMetricsScope().counter(MetricsType.WORKER_START_COUNTER).inc(1);
    }
  }

  public byte[] queryWorkflowExecution(WorkflowExecution exec, String queryType, byte[] args)
      throws Exception {
    GetWorkflowExecutionHistoryResponse historyResponse =
        WorkflowExecutionUtils.getHistoryPage(null, service, domain, exec);
    History history = historyResponse.getHistory();
    WorkflowExecutionHistory workflowExecutionHistory =
        new WorkflowExecutionHistory(exec.getWorkflowId(), exec.getRunId(), history.getEvents());
    return queryWorkflowExecution(
        queryType, args, workflowExecutionHistory, historyResponse.getNextPageToken());
  }

  public byte[] queryWorkflowExecution(String jsonSerializedHistory, String queryType, byte[] args)
      throws Exception {
    WorkflowExecutionHistory history = WorkflowExecutionHistory.fromJson(jsonSerializedHistory);
    return queryWorkflowExecution(queryType, args, history, null);
  }

  public byte[] queryWorkflowExecution(
      WorkflowExecutionHistory history, String queryType, byte[] args) throws Exception {
    return queryWorkflowExecution(queryType, args, history, null);
  }

  private byte[] queryWorkflowExecution(
      String queryType, byte[] args, WorkflowExecutionHistory history, byte[] nextPageToken)
      throws Exception {
    PollForDecisionTaskResponse task;
    task = new PollForDecisionTaskResponse();
    task.setWorkflowExecution(history.getWorkflowExecution());
    task.setStartedEventId(Long.MAX_VALUE);
    task.setPreviousStartedEventId(Long.MAX_VALUE);
    task.setNextPageToken(nextPageToken);
    WorkflowQuery query = new WorkflowQuery();
    query.setQueryType(queryType).setQueryArgs(args);
    task.setQuery(query);
    List<HistoryEvent> events = history.getEvents();
    HistoryEvent startedEvent = events.get(0);
    WorkflowExecutionStartedEventAttributes started =
        startedEvent.getWorkflowExecutionStartedEventAttributes();
    if (started == null) {
      throw new IllegalStateException(
          "First event of the history is not  WorkflowExecutionStarted: " + startedEvent);
    }
    WorkflowType workflowType = started.getWorkflowType();
    task.setWorkflowType(workflowType);
    task.setHistory(new History().setEvents(events));
    DecisionTaskHandler.Result result = handler.handleDecisionTask(task);
    if (result.getQueryCompleted() != null) {
      RespondQueryTaskCompletedRequest r = result.getQueryCompleted();
      if (r.getErrorMessage() != null) {
        throw new RuntimeException(
            "query failure for "
                + history.getWorkflowExecution()
                + ", queryType="
                + queryType
                + ", args="
                + Arrays.toString(args)
                + ", error="
                + r.getErrorMessage());
      }
      return r.getQueryResult();
    }
    throw new RuntimeException("Query returned wrong response: " + result);
  }

  @Override
  public void shutdown() {
    if (poller != null) {
      poller.shutdown();
    }
  }

  @Override
  public void shutdownNow() {
    if (poller != null) {
      poller.shutdownNow();
    }
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    if (poller == null) {
      return true;
    }
    return poller.awaitTermination(timeout, unit);
  }

  @Override
  public boolean shutdownAndAwaitTermination(long timeout, TimeUnit unit)
      throws InterruptedException {
    if (poller == null) {
      return true;
    }
    return poller.shutdownAndAwaitTermination(timeout, unit);
  }

  @Override
  public boolean isRunning() {
    if (poller == null) {
      return false;
    }
    return poller.isRunning();
  }

  @Override
  public void suspendPolling() {
    if (poller != null) {
      poller.suspendPolling();
    }
  }

  @Override
  public void resumePolling() {
    if (poller != null) {
      poller.resumePolling();
    }
  }

  @Override
  public void accept(PollForDecisionTaskResponse pollForDecisionTaskResponse) {
    pollTaskExecutor.accept(pollForDecisionTaskResponse);
  }

  private class TaskHandlerImpl
      implements PollTaskExecutor.TaskHandler<PollForDecisionTaskResponse> {

    final DecisionTaskHandler handler;

    private TaskHandlerImpl(DecisionTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(PollForDecisionTaskResponse task) throws Exception {
      MDC.put(LoggerTag.WORKFLOW_ID, task.getWorkflowExecution().getWorkflowId());
      MDC.put(LoggerTag.WORKFLOW_TYPE, task.getWorkflowType().getName());
      MDC.put(LoggerTag.RUN_ID, task.getWorkflowExecution().getRunId());
      try {
        Stopwatch sw =
            options.getMetricsScope().timer(MetricsType.DECISION_EXECUTION_LATENCY).start();
        DecisionTaskHandler.Result response = handler.handleDecisionTask(task);
        sw.stop();

        sw = options.getMetricsScope().timer(MetricsType.DECISION_RESPONSE_LATENCY).start();
        sendReply(service, task.getTaskToken(), response);
        sw.stop();

        options.getMetricsScope().counter(MetricsType.DECISION_TASK_COMPLETED_COUNTER).inc(1);
      } finally {
        MDC.remove(LoggerTag.WORKFLOW_ID);
        MDC.remove(LoggerTag.WORKFLOW_TYPE);
        MDC.remove(LoggerTag.RUN_ID);
      }
    }

    @Override
    public Throwable wrapFailure(PollForDecisionTaskResponse task, Throwable failure) {
      WorkflowExecution execution = task.getWorkflowExecution();
      return new RuntimeException(
          "Failure processing decision task. WorkflowID="
              + execution.getWorkflowId()
              + ", RunID="
              + execution.getRunId(),
          failure);
    }

    private void sendReply(
        IWorkflowService service, byte[] taskToken, DecisionTaskHandler.Result response)
        throws TException {
      RetryOptions ro = response.getRequestRetryOptions();
      RespondDecisionTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        ro = options.getReportCompletionRetryOptions().merge(ro);
        taskCompleted.setIdentity(options.getIdentity());
        taskCompleted.setTaskToken(taskToken);
        Retryer.retry(ro, () -> service.RespondDecisionTaskCompleted(taskCompleted));
      } else {
        RespondDecisionTaskFailedRequest taskFailed = response.getTaskFailed();
        if (taskFailed != null) {
          ro = options.getReportFailureRetryOptions().merge(ro);
          taskFailed.setIdentity(options.getIdentity());
          taskFailed.setTaskToken(taskToken);
          Retryer.retry(ro, () -> service.RespondDecisionTaskFailed(taskFailed));
        } else {
          RespondQueryTaskCompletedRequest queryCompleted = response.getQueryCompleted();
          if (queryCompleted != null) {
            queryCompleted.setTaskToken(taskToken);
            // Do not retry query response.
            service.RespondQueryTaskCompleted(queryCompleted);
          }
        }
      }
      // Manual activity completion
    }
  }
}
