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
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.statemachines.EntityManagerListener;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.internal.worker.LocalActivityWorker;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

  private final BiFunction<LocalActivityWorker.Task, Duration, Boolean> localActivityTaskPoller;

  private final Map<String, WorkflowQueryResult> queryResults = new HashMap<>();

  private final DataConverter converter;

  private final WorkflowStateMachines workflowStateMachines;

  private final HistoryEvent firstEvent;

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
      BiFunction<LocalActivityWorker.Task, Duration, Boolean> localActivityTaskPoller) {
    this.service = service;
    this.namespace = namespace;
    firstEvent = workflowTask.getHistory().getEvents(0);

    if (!firstEvent.hasWorkflowExecutionStartedEventAttributes()) {
      throw new IllegalArgumentException(
          "First event in the history is not WorkflowExecutionStarted");
    }
    startedEvent = firstEvent.getWorkflowExecutionStartedEventAttributes();
    this.workflowStateMachines = new WorkflowStateMachines(new EntityManagerListenerImpl());
    this.metricsScope = metricsScope;
    this.converter = options.getDataConverter();
    this.localActivityTaskPoller = localActivityTaskPoller;

    ReplayWorkflowContextImpl context =
        new ReplayWorkflowContextImpl(
            workflowStateMachines,
            namespace,
            startedEvent,
            workflowTask.getWorkflowExecution(),
            Timestamps.toMillis(firstEvent.getEventTime()),
            options,
            metricsScope);

    replayWorkflowExecutor =
        new ReplayWorkflowExecutor(workflow, metricsScope, workflowStateMachines, context);

    localActivityCompletionSink = historyEvent -> localActivityCompletionQueue.add(historyEvent);
  }

  private void handleEvent(HistoryEvent event, boolean hasNextEvent) {
    workflowStateMachines.handleEvent(event, hasNextEvent);
  }

  @Override
  public WorkflowTaskResult handleWorkflowTask(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask) {
    lock.lock();
    try {
      queryResults.clear();
      long startTime = System.currentTimeMillis();
      handleWorkflowTaskImpl(workflowTask);
      processLocalActivityRequests(startTime);
      List<Command> commands = workflowStateMachines.takeCommands();
      executeQueries(workflowTask.getQueriesMap());
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

  private void handleWorkflowTaskImpl(PollWorkflowTaskQueueResponseOrBuilder workflowTask) {
    Stopwatch sw = metricsScope.timer(MetricsType.WORKFLOW_TASK_REPLAY_LATENCY).start();
    boolean timerStopped = false;
    try {
      workflowStateMachines.setStartedIds(
          workflowTask.getPreviousStartedEventId(), workflowTask.getStartedEventId());
      Iterator<HistoryEvent> historyEvents =
          new WorkflowHistoryIterator(
              service,
              namespace,
              workflowTask,
              toJavaDuration(startedEvent.getWorkflowTaskTimeout()),
              metricsScope);
      while (historyEvents.hasNext()) {
        HistoryEvent event = historyEvents.next();
        handleEvent(event, historyEvents.hasNext());
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
          // Wrap any failure into InternalWorkflowTaskException to support specifying them
          // in the implementation options.
          if (!(e instanceof InternalWorkflowTaskException)) {
            e = new InternalWorkflowTaskException(e);
          }
          throw replayWorkflowExecutor.mapUnexpectedException(e);
        }
      }
      metricsScope.counter(MetricsType.WORKFLOW_TASK_NO_COMPLETION_COUNTER).inc(1);
      throw wrap(e);
    } finally {
      if (!timerStopped) {
        sw.stop();
      }
      if (replayWorkflowExecutor.isCompleted()) {
        close();
      }
    }
  }

  private void executeQueries(Map<String, WorkflowQuery> queries) {
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
                .setErrorMessage(e.toString() + "\n" + stackTrace)
                .build());
      }
    }
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

  @Override
  public Optional<Payloads> handleQueryWorkflowTask(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, WorkflowQuery query) {
    lock.lock();
    try {
      AtomicReference<Optional<Payloads>> result = new AtomicReference<>();
      handleWorkflowTaskImpl(workflowTask);
      result.set(replayWorkflowExecutor.query(query));
      return result.get();
    } finally {
      lock.unlock();
    }
  }

  private void processLocalActivityRequests(long startTime) {
    long forcedDecisionTimeout =
        (long)
            (Durations.toMillis(startedEvent.getWorkflowTaskTimeout())
                * FORCED_DECISION_TIME_COEFFICIENT);
    long nextForcedDecisionTime = startTime + forcedDecisionTimeout;
    while (true) {
      List<ExecuteLocalActivityParameters> laRequests =
          workflowStateMachines.takeLocalActivityRequests();
      for (ExecuteLocalActivityParameters laRequest : laRequests) {
        // TODO(maxim): In the presence of workflow task heartbeat this timeout doesn't make
        // much sense. I believe we should add ScheduleToStart timeout for the local activities
        // as well.
        Duration maxWaitTime =
            Duration.ofMillis(nextForcedDecisionTime - System.currentTimeMillis());
        if (maxWaitTime.isNegative()) {
          maxWaitTime = Duration.ZERO;
        }
        boolean accepted =
            localActivityTaskPoller.apply(
                new LocalActivityWorker.Task(laRequest, localActivityCompletionSink), maxWaitTime);
        localActivityTaskCount++;
        if (!accepted) {
          throw new Error("Unable to schedule local activity for execution");
        }
      }
      if (localActivityTaskCount == 0) {
        // No outstanding local activity requests
        break;
      }
      waitAndProcessLocalActivityCompletion(nextForcedDecisionTime);
      if (nextForcedDecisionTime <= System.currentTimeMillis()) {
        break;
      }
    }
  }

  private void waitAndProcessLocalActivityCompletion(long nextForcedDecisionTime) {
    long maxWaitTime = nextForcedDecisionTime - System.currentTimeMillis();
    if (maxWaitTime <= 0) {
      return;
    }
    ActivityTaskHandler.Result laCompletion;
    try {
      laCompletion = localActivityCompletionQueue.poll(maxWaitTime, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      // TODO(maxim): interrupt when worker shutdown is called
      throw new IllegalStateException("interrupted", e);
    }
    if (laCompletion == null) {
      // Need to force a new task as nextForcedDecisionTime has passed.
      return;
    }
    localActivityTaskCount--;
    workflowStateMachines.handleLocalActivityCompletion(laCompletion);
  }

  private class EntityManagerListenerImpl implements EntityManagerListener {

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
