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

package io.temporal.internal.replay;

import static io.temporal.internal.common.ProtobufTimeUtils.toJavaDuration;
import static io.temporal.serviceclient.CheckedExceptionWrapper.wrap;

import com.google.common.base.Throwables;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import io.temporal.api.command.v1.Command;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.QueryResultType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.query.v1.WorkflowQueryResult;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.internal.statemachines.StatesMachinesCallback;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.internal.worker.*;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/**
 * Implements workflow executor that relies on replay of a workflow code. An instance of this class
 * is created per cached workflow run.
 */
class ReplayWorkflowRunTaskHandler implements WorkflowRunTaskHandler {

  /** Force new decision task after workflow task timeout multiplied by this coefficient. */
  public static final double FORCED_DECISION_TIME_COEFFICIENT = 4d / 5d;

  private final WorkflowServiceStubs service;

  private final String namespace;

  private final Scope metricsScope;

  private final WorkflowExecutionStartedEventAttributes startedEvent;

  private final Lock lock = new ReentrantLock();

  private final Functions.Proc1<ActivityTaskHandler.Result> localActivityCompletionSink;

  private final BlockingQueue<ActivityTaskHandler.Result> localActivityCompletionQueue =
      new LinkedBlockingDeque<>();

  private final BiFunction<LocalActivityTask, Duration, Boolean> localActivityTaskPoller;

  private final ReplayWorkflow workflow;

  private final WorkflowStateMachines workflowStateMachines;

  /** Number of non completed local activity tasks */
  private int localActivityTaskCount;

  private final ReplayWorkflowExecutor replayWorkflowExecutor;

  ReplayWorkflowRunTaskHandler(
      WorkflowServiceStubs service,
      String namespace,
      ReplayWorkflow workflow,
      PollWorkflowTaskQueueResponseOrBuilder workflowTask,
      SingleWorkerOptions options,
      Scope metricsScope,
      BiFunction<LocalActivityTask, Duration, Boolean> localActivityTaskPoller) {
    this.service = service;
    this.namespace = namespace;

    HistoryEvent firstEvent = workflowTask.getHistory().getEvents(0);
    if (!firstEvent.hasWorkflowExecutionStartedEventAttributes()) {
      throw new IllegalArgumentException(
          "First event in the history is not WorkflowExecutionStarted");
    }
    this.startedEvent = firstEvent.getWorkflowExecutionStartedEventAttributes();

    this.metricsScope = metricsScope;
    this.localActivityTaskPoller = localActivityTaskPoller;
    this.workflow = workflow;

    this.workflowStateMachines = new WorkflowStateMachines(new StatesMachinesCallbackImpl());
    ReplayWorkflowContextImpl context =
        new ReplayWorkflowContextImpl(
            workflowStateMachines,
            namespace,
            startedEvent,
            workflowTask.getWorkflowExecution(),
            Timestamps.toMillis(firstEvent.getEventTime()),
            options,
            metricsScope);

    this.replayWorkflowExecutor =
        new ReplayWorkflowExecutor(workflow, workflowStateMachines, context);
    this.localActivityCompletionSink = localActivityCompletionQueue::add;
  }

  @Override
  public WorkflowTaskResult handleWorkflowTask(PollWorkflowTaskQueueResponseOrBuilder workflowTask)
      throws InterruptedException {
    lock.lock();
    try {
      long startTimeNanos = System.nanoTime();
      handleWorkflowTaskImpl(workflowTask);
      processLocalActivityRequests(startTimeNanos);
      List<Command> commands = workflowStateMachines.takeCommands();
      if (replayWorkflowExecutor.isCompleted()) {
        // it's important for query, otherwise the WorkflowRunTaskHandler is responsible for closing
        // and invalidation
        close();
      }
      Map<String, WorkflowQueryResult> queryResults = executeQueries(workflowTask.getQueriesMap());
      return WorkflowTaskResult.newBuilder()
          .setCommands(commands)
          .setQueryResults(queryResults)
          .setFinalCommand(replayWorkflowExecutor.isCompleted())
          .setForceWorkflowTask(localActivityTaskCount > 0 && !replayWorkflowExecutor.isCompleted())
          .build();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public QueryResult handleQueryWorkflowTask(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, WorkflowQuery query) {
    lock.lock();
    try {
      handleWorkflowTaskImpl(workflowTask);
      if (replayWorkflowExecutor.isCompleted()) {
        // it's important for query, otherwise the WorkflowRunTaskHandler is responsible for closing
        // and invalidation
        close();
      }
      Optional<Payloads> resultPayloads = replayWorkflowExecutor.query(query);
      return new QueryResult(resultPayloads, replayWorkflowExecutor.isCompleted());
    } finally {
      lock.unlock();
    }
  }

  private void handleWorkflowTaskImpl(PollWorkflowTaskQueueResponseOrBuilder workflowTask) {
    Stopwatch sw = metricsScope.timer(MetricsType.WORKFLOW_TASK_REPLAY_LATENCY).start();
    boolean timerStopped = false;
    try {
      workflowStateMachines.setStartedIds(
          workflowTask.getPreviousStartedEventId(), workflowTask.getStartedEventId());
      WorkflowHistoryIterator historyEvents =
          new WorkflowHistoryIterator(
              service,
              namespace,
              workflowTask,
              toJavaDuration(startedEvent.getWorkflowTaskTimeout()),
              metricsScope);
      while (historyEvents.hasNext()) {
        HistoryEvent event = historyEvents.next();
        workflowStateMachines.handleEvent(event, historyEvents.hasNext());
        if (!timerStopped && !workflowStateMachines.isReplaying()) {
          sw.stop();
          timerStopped = true;
        }
      }
    } catch (Throwable e) {
      // Fail workflow if exception is of the specified type
      WorkflowImplementationOptions implementationOptions =
          this.replayWorkflowExecutor.getWorkflowImplementationOptions();
      Class<? extends Throwable>[] failTypes =
          implementationOptions.getFailWorkflowExceptionTypes();
      for (Class<? extends Throwable> failType : failTypes) {
        if (failType.isAssignableFrom(e.getClass())) {
          throw new WorkflowExecutionException(workflow.mapExceptionToFailure(e));
        }
      }
      throw wrap(e);
    } finally {
      if (!timerStopped) {
        sw.stop();
      }
    }
  }

  private Map<String, WorkflowQueryResult> executeQueries(Map<String, WorkflowQuery> queries) {
    Map<String, WorkflowQueryResult> queryResults = new HashMap<>();
    for (Map.Entry<String, WorkflowQuery> entry : queries.entrySet()) {
      WorkflowQuery query = entry.getValue();
      try {
        Optional<Payloads> queryResult = replayWorkflowExecutor.query(query);
        WorkflowQueryResult.Builder result =
            WorkflowQueryResult.newBuilder()
                .setResultType(QueryResultType.QUERY_RESULT_TYPE_ANSWERED);
        if (queryResult.isPresent()) {
          result.setAnswer(queryResult.get());
        }
        queryResults.put(entry.getKey(), result.build());
      } catch (Exception e) {
        String stackTrace = Throwables.getStackTraceAsString(e);
        queryResults.put(
            entry.getKey(),
            WorkflowQueryResult.newBuilder()
                .setResultType(QueryResultType.QUERY_RESULT_TYPE_FAILED)
                .setErrorMessage(e + "\n" + stackTrace)
                .build());
      }
    }
    return queryResults;
  }

  @Override
  public void close() {
    lock.lock();
    try {
      replayWorkflowExecutor.close();
    } finally {
      lock.unlock();
    }
  }

  private void processLocalActivityRequests(long startTimeNs) throws InterruptedException {
    long durationUntilWFTHeartbeatNs =
        (long)
            (Durations.toNanos(startedEvent.getWorkflowTaskTimeout())
                * FORCED_DECISION_TIME_COEFFICIENT);

    long nextWFTHeartbeatTimeNs = startTimeNs + durationUntilWFTHeartbeatNs;
    while (true) {
      List<ExecuteLocalActivityParameters> laRequests =
          workflowStateMachines.takeLocalActivityRequests();
      for (ExecuteLocalActivityParameters laRequest : laRequests) {
        // TODO(maxim): In the presence of workflow task heartbeat this timeout doesn't make
        // much sense. I believe we should add ScheduleToStart timeout for the local activities
        // as well.
        Duration maxWaitTime = Duration.ofNanos(nextWFTHeartbeatTimeNs - System.nanoTime());
        if (maxWaitTime.isNegative()) {
          maxWaitTime = Duration.ZERO;
        }
        boolean accepted =
            localActivityTaskPoller.apply(
                new LocalActivityTask(laRequest, localActivityCompletionSink), maxWaitTime);
        localActivityTaskCount++;
        if (!accepted) {
          throw new IllegalStateException(
              "Unable to schedule local activity for execution, no more slots available and local activity task queue is full");
        }
      }
      if (localActivityTaskCount == 0) {
        // No outstanding local activity requests
        break;
      }
      waitAndProcessLocalActivityCompletion(nextWFTHeartbeatTimeNs);
      if (nextWFTHeartbeatTimeNs <= System.nanoTime()) {
        break;
      }
    }
  }

  private void waitAndProcessLocalActivityCompletion(long nextForcedDecisionTimeNs)
      throws InterruptedException {
    long maxWaitTimeNs = nextForcedDecisionTimeNs - System.nanoTime();
    if (maxWaitTimeNs <= 0) {
      return;
    }
    ActivityTaskHandler.Result laCompletion;
    laCompletion = localActivityCompletionQueue.poll(maxWaitTimeNs, TimeUnit.NANOSECONDS);
    if (laCompletion == null) {
      // Need to force a new task as nextForcedDecisionTime has passed.
      return;
    }
    localActivityTaskCount--;
    workflowStateMachines.handleLocalActivityCompletion(laCompletion);
  }

  private class StatesMachinesCallbackImpl implements StatesMachinesCallback {

    @Override
    public void start(HistoryEvent startWorkflowEvent) {
      replayWorkflowExecutor.start(startWorkflowEvent);
    }

    @Override
    public void eventLoop() {
      replayWorkflowExecutor.eventLoop();
    }

    @Override
    public void signal(HistoryEvent signalEvent) {
      replayWorkflowExecutor.handleWorkflowExecutionSignaled(signalEvent);
    }

    @Override
    public void cancel(HistoryEvent cancelEvent) {
      replayWorkflowExecutor.handleWorkflowExecutionCancelRequested(cancelEvent);
    }
  }
}
