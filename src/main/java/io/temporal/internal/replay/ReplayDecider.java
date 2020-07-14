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

import static io.temporal.internal.metrics.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;
import static io.temporal.worker.WorkflowErrorPolicy.FailWorkflow;

import com.google.common.base.Throwables;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import io.grpc.Status;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.QueryResultType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.TimerFiredEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.query.v1.WorkflowQueryResult;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.GrpcRetryer;
import io.temporal.internal.common.OptionsUtils;
import io.temporal.internal.common.RpcRetryOptions;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.HistoryHelper.DecisionEvents;
import io.temporal.internal.worker.LocalActivityWorker;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.internal.worker.WorkflowTaskWithHistoryIterator;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Implements decider that relies on replay of a workflow code. An instance of this class is created
 * per decision.
 */
class ReplayDecider implements Decider {

  private static final int MAXIMUM_PAGE_SIZE = 10000;

  private final DecisionsHelper decisionsHelper;
  private final ReplayWorkflowContextImpl context;
  private final WorkflowServiceStubs service;
  private final ReplayWorkflow workflow;
  private boolean cancelRequested;
  private boolean completed;
  private WorkflowExecutionException failure;
  private long wakeUpTime;
  private Consumer<Exception> timerCancellationHandler;
  private final Scope metricsScope;
  private final long wfStartTimeNanos;
  private final WorkflowExecutionStartedEventAttributes startedEvent;
  private final Lock lock = new ReentrantLock();
  private final Consumer<HistoryEvent> localActivityCompletionSink;
  private final Map<String, WorkflowQueryResult> queryResults = new HashMap<>();
  private final DataConverter converter;

  ReplayDecider(
      WorkflowServiceStubs service,
      String namespace,
      ReplayWorkflow workflow,
      PollWorkflowTaskQueueResponse.Builder workflowTask,
      SingleWorkerOptions options,
      Scope metricsScope,
      BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller) {
    this.service = service;
    this.workflow = workflow;
    this.decisionsHelper = new DecisionsHelper(workflowTask);
    this.metricsScope = metricsScope;
    this.converter = options.getDataConverter();

    HistoryEvent firstEvent = workflowTask.getHistory().getEvents(0);
    if (!firstEvent.hasWorkflowExecutionStartedEventAttributes()) {
      throw new IllegalArgumentException(
          "First event in the history is not WorkflowExecutionStarted");
    }
    startedEvent = firstEvent.getWorkflowExecutionStartedEventAttributes();
    wfStartTimeNanos = firstEvent.getTimestamp();

    context =
        new ReplayWorkflowContextImpl(
            decisionsHelper,
            namespace,
            startedEvent,
            Duration.ofNanos(firstEvent.getTimestamp()).toMillis(),
            options,
            metricsScope,
            laTaskPoller,
            this);

    localActivityCompletionSink =
        historyEvent -> {
          lock.lock();
          try {
            processEvent(historyEvent);
          } finally {
            lock.unlock();
          }
        };
  }

  Lock getLock() {
    return lock;
  }

  private void handleWorkflowExecutionStarted(HistoryEvent event) {
    workflow.start(event, context);
  }

  private void processEvent(HistoryEvent event) {
    EventType eventType = event.getEventType();
    switch (eventType) {
      case EVENT_TYPE_ACTIVITY_TASK_CANCELED:
        context.handleActivityTaskCanceled(event);
        break;
      case EVENT_TYPE_ACTIVITY_TASK_COMPLETED:
        context.handleActivityTaskCompleted(event);
        break;
      case EVENT_TYPE_ACTIVITY_TASK_FAILED:
        context.handleActivityTaskFailed(event);
        break;
      case EVENT_TYPE_ACTIVITY_TASK_STARTED:
        decisionsHelper.handleActivityTaskStarted(event);
        break;
      case EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT:
        context.handleActivityTaskTimedOut(event);
        break;
      case EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
        context.handleChildWorkflowExecutionCancelRequested(event);
        break;
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED:
        context.handleChildWorkflowExecutionCanceled(event);
        break;
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED:
        context.handleChildWorkflowExecutionCompleted(event);
        break;
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED:
        context.handleChildWorkflowExecutionFailed(event);
        break;
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED:
        context.handleChildWorkflowExecutionStarted(event);
        break;
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED:
        context.handleChildWorkflowExecutionTerminated(event);
        break;
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT:
        context.handleChildWorkflowExecutionTimedOut(event);
        break;
      case EVENT_TYPE_WORKFLOW_TASK_COMPLETED:
        // NOOP
        break;
      case EVENT_TYPE_WORKFLOW_TASK_SCHEDULED:
        // NOOP
        break;
      case EVENT_TYPE_WORKFLOW_TASK_STARTED:
        throw new IllegalArgumentException("not expected");
      case EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT:
        // Handled in the processEvent(event)
        break;
      case EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_SIGNALED:
        context.handleExternalWorkflowExecutionSignaled(event);
        break;
      case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED:
        context.handleStartChildWorkflowExecutionFailed(event);
        break;
      case EVENT_TYPE_TIMER_FIRED:
        handleTimerFired(event);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
        handleWorkflowExecutionCancelRequested(event);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED:
        handleWorkflowExecutionSignaled(event);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_STARTED:
        handleWorkflowExecutionStarted(event);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
        // NOOP
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
        decisionsHelper.handleWorkflowExecutionCompleted(event);
        break;
      case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED:
        d:
        decisionsHelper.handleActivityTaskScheduled(event);
        break;
      case EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED:
        decisionsHelper.handleActivityTaskCancelRequested(event);
        break;
      case EVENT_TYPE_REQUEST_CANCEL_ACTIVITY_TASK_FAILED:
        throw new Error("unexpected event");
      case EVENT_TYPE_MARKER_RECORDED:
        context.handleMarkerRecorded(event);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED:
        decisionsHelper.handleWorkflowExecutionCompleted(event);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
        decisionsHelper.handleWorkflowExecutionCompleted(event);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
        decisionsHelper.handleWorkflowExecutionCompleted(event);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW:
        decisionsHelper.handleWorkflowExecutionCompleted(event);
        break;
      case EVENT_TYPE_TIMER_STARTED:
        decisionsHelper.handleTimerStarted(event);
        break;
      case EVENT_TYPE_TIMER_CANCELED:
        context.handleTimerCanceled(event);
        break;
      case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
        decisionsHelper.handleSignalExternalWorkflowExecutionInitiated(event);
        break;
      case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED:
        context.handleSignalExternalWorkflowExecutionFailed(event);
        break;
      case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
        decisionsHelper.handleRequestCancelExternalWorkflowExecutionInitiated(event);
        break;
      case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED:
        decisionsHelper.handleRequestCancelExternalWorkflowExecutionFailed(event);
        break;
      case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED:
        decisionsHelper.handleStartChildWorkflowExecutionInitiated(event);
        break;
      case EVENT_TYPE_CANCEL_TIMER_FAILED:
        decisionsHelper.handleCancelTimerFailed(event);
        break;
      case EVENT_TYPE_WORKFLOW_TASK_FAILED:
        context.handleWorkflowTaskFailed(event);
        break;
      case EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
        context.handleUpsertSearchAttributes(event);
        break;
    }
  }

  private void eventLoop() {
    if (completed) {
      return;
    }
    try {
      completed = workflow.eventLoop();
    } catch (Error e) {
      throw e;
    } catch (WorkflowExecutionException e) {
      failure = e;
      completed = true;
    } catch (CanceledFailure e) {
      if (!cancelRequested) {
        failure = workflow.mapUnexpectedException(e);
      }
      completed = true;
    } catch (Throwable e) {
      // can cast as Error is caught above.
      failure = workflow.mapUnexpectedException(e);
      completed = true;
    }
  }

  private void mayBeCompleteWorkflow() {
    if (completed) {
      completeWorkflow();
    } else {
      updateTimers();
    }
  }

  private void completeWorkflow() {
    if (failure != null) {
      decisionsHelper.failWorkflowExecution(failure);
      metricsScope.counter(MetricsType.WORKFLOW_FAILED_COUNTER).inc(1);
    } else if (cancelRequested) {
      decisionsHelper.cancelWorkflowExecution();
      metricsScope.counter(MetricsType.WORKFLOW_CANCELLED_COUNTER).inc(1);
    } else {
      ContinueAsNewWorkflowExecutionCommandAttributes attributes =
          context.getContinueAsNewOnCompletion();
      if (attributes != null) {
        decisionsHelper.continueAsNewWorkflowExecution(attributes);
        metricsScope.counter(MetricsType.WORKFLOW_CONTINUE_AS_NEW_COUNTER).inc(1);
      } else {
        Optional<Payloads> workflowOutput = workflow.getOutput();
        decisionsHelper.completeWorkflowExecution(workflowOutput);
        metricsScope.counter(MetricsType.WORKFLOW_COMPLETED_COUNTER).inc(1);
      }
    }

    long nanoTime = TimeUnit.NANOSECONDS.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    com.uber.m3.util.Duration d = com.uber.m3.util.Duration.ofNanos(nanoTime - wfStartTimeNanos);
    metricsScope.timer(MetricsType.WORKFLOW_E2E_LATENCY).record(d);
  }

  private void updateTimers() {
    long nextWakeUpTime = workflow.getNextWakeUpTime();
    if (nextWakeUpTime == 0) {
      if (timerCancellationHandler != null) {
        timerCancellationHandler.accept(null);
        timerCancellationHandler = null;
      }
      wakeUpTime = nextWakeUpTime;
      return;
    }
    if (wakeUpTime == nextWakeUpTime && timerCancellationHandler != null) {
      return; // existing timer
    }
    long delayMilliseconds = nextWakeUpTime - context.currentTimeMillis();
    if (delayMilliseconds < 0) {
      throw new IllegalStateException("Negative delayMilliseconds=" + delayMilliseconds);
    }
    // Round up to the nearest second as we don't want to deliver a timer
    // earlier than requested.
    long delaySeconds = OptionsUtils.roundUpToSeconds(Duration.ofMillis(delayMilliseconds));
    if (timerCancellationHandler != null) {
      timerCancellationHandler.accept(null);
      timerCancellationHandler = null;
    }
    wakeUpTime = nextWakeUpTime;
    timerCancellationHandler =
        context.createTimer(
            delaySeconds,
            (t) -> {
              // Intentionally left empty.
              // Timer ensures that decision is scheduled at the time workflow can make progress.
              // But no specific timer related action is necessary as Workflow.sleep is just a
              // Workflow.await with a time based condition.
            });
  }

  private void handleWorkflowExecutionCancelRequested(HistoryEvent event) {
    context.setCancelRequested(true);
    String cause = event.getWorkflowExecutionCancelRequestedEventAttributes().getCause();
    workflow.cancel(cause);
    cancelRequested = true;
  }

  private void handleTimerFired(HistoryEvent event) {
    TimerFiredEventAttributes attributes = event.getTimerFiredEventAttributes();
    String timerId = attributes.getTimerId();
    if (timerId.equals(DecisionsHelper.FORCE_IMMEDIATE_DECISION_TIMER)) {
      return;
    }
    context.handleTimerFired(attributes);
  }

  private void handleWorkflowExecutionSignaled(HistoryEvent event) {
    assert (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED);
    final WorkflowExecutionSignaledEventAttributes signalAttributes =
        event.getWorkflowExecutionSignaledEventAttributes();
    if (completed) {
      throw new IllegalStateException("Signal received after workflow is closed.");
    }
    Optional<Payloads> input =
        signalAttributes.hasInput() ? Optional.of(signalAttributes.getInput()) : Optional.empty();
    this.workflow.handleSignal(signalAttributes.getSignalName(), input, event.getEventId());
  }

  @Override
  public DecisionResult decide(PollWorkflowTaskQueueResponseOrBuilder workflowTask)
      throws Throwable {
    lock.lock();
    try {
      queryResults.clear();
      boolean forceCreateNewWorkflowTask = decideImpl(workflowTask, null);
      return new DecisionResult(
          decisionsHelper.getDecisions(), queryResults, forceCreateNewWorkflowTask, completed);
    } finally {
      lock.unlock();
    }
  }

  // Returns boolean to indicate whether we need to force create new workflow task for local
  // activity heartbeating.
  private boolean decideImpl(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, Functions.Proc legacyQueryCallback)
      throws Throwable {
    boolean forceCreateNewWorkflowTask = false;
    Stopwatch sw = metricsScope.timer(MetricsType.WORKFLOW_TASK_REPLAY_LATENCY).start();
    boolean timerStopped = false;
    try {
      long startTime = System.currentTimeMillis();
      WorkflowTaskWithHistoryIterator workflowTaskWithHistoryIterator =
          new WorkflowTaskWithHistoryIteratorImpl(
              workflowTask, Duration.ofSeconds(startedEvent.getWorkflowTaskTimeoutSeconds()));
      HistoryHelper historyHelper =
          new HistoryHelper(
              workflowTaskWithHistoryIterator, context.getReplayCurrentTimeMilliseconds());
      Iterator<DecisionEvents> iterator = historyHelper.getIterator();
      if (decisionsHelper.getLastStartedEventId() > 0
          && decisionsHelper.getLastStartedEventId() != historyHelper.getPreviousStartedEventId()
          && workflowTask.getHistory().getEventsCount() > 0) {
        throw new IllegalStateException(
            String.format(
                "ReplayDecider processed up to event id %d. History's previous started event id is %d",
                decisionsHelper.getLastStartedEventId(),
                historyHelper.getPreviousStartedEventId()));
      }
      while (iterator.hasNext()) {
        DecisionEvents decision = iterator.next();
        if (!timerStopped && !decision.isReplay()) {
          sw.stop();
          timerStopped = true;
        }
        context.setReplaying(decision.isReplay());
        context.setReplayCurrentTimeMilliseconds(decision.getReplayCurrentTimeMilliseconds());

        decisionsHelper.handleWorkflowTaskStartedEvent(decision);
        // Markers must be cached first as their data is needed when processing events.
        for (HistoryEvent event : decision.getMarkers()) {
          if (!event
              .getMarkerRecordedEventAttributes()
              .getMarkerName()
              .equals(ClockDecisionContext.LOCAL_ACTIVITY_MARKER_NAME)) {
            processEvent(event);
          }
        }

        for (HistoryEvent event : decision.getEvents()) {
          processEvent(event);
        }

        forceCreateNewWorkflowTask =
            processEventLoop(
                startTime,
                startedEvent.getWorkflowTaskTimeoutSeconds(),
                decision,
                workflowTask.hasQuery());

        mayBeCompleteWorkflow();
        if (decision.isReplay()) {
          decisionsHelper.notifyDecisionSent();
        }
        // Updates state machines with results of the previous decisions
        for (HistoryEvent event : decision.getDecisionEvents()) {
          processEvent(event);
        }
        // Reset state to before running the event loop
        decisionsHelper.handleWorkflowTaskStartedEvent(decision);
      }
      return forceCreateNewWorkflowTask;
    } catch (Error e) {
      if (this.workflow.getWorkflowImplementationOptions().getWorkflowErrorPolicy()
          == FailWorkflow) {
        // fail workflow
        failure = workflow.mapError(e);
        completed = true;
        completeWorkflow();
        return false;
      } else {
        metricsScope.counter(MetricsType.WORKFLOW_TASK_FAILURE_COUNTER).inc(1);
        // fail decision, not a workflow
        throw e;
      }
    } finally {
      if (!timerStopped) {
        sw.stop();
      }
      Map<String, WorkflowQuery> queries = workflowTask.getQueriesMap();
      for (Map.Entry<String, WorkflowQuery> entry : queries.entrySet()) {
        WorkflowQuery query = entry.getValue();
        try {
          Optional<Payloads> queryResult = workflow.query(query);
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
                  .setErrorMessage(e.getMessage())
                  .setAnswer(converter.toPayloads(stackTrace).get())
                  .build());
        }
      }
      if (legacyQueryCallback != null) {
        legacyQueryCallback.apply();
      }
      if (completed) {
        close();
      }
    }
  }

  private boolean processEventLoop(
      long startTime, int decisionTimeoutSecs, DecisionEvents decision, boolean isQuery)
      throws Throwable {
    eventLoop();

    if (decision.isReplay() || isQuery) {
      return replayLocalActivities(decision);
    } else {
      return executeLocalActivities(startTime, decisionTimeoutSecs);
    }
  }

  private boolean replayLocalActivities(DecisionEvents decision) throws Throwable {
    List<HistoryEvent> localActivityMarkers = new ArrayList<>();
    for (HistoryEvent event : decision.getMarkers()) {
      if (event
          .getMarkerRecordedEventAttributes()
          .getMarkerName()
          .equals(ClockDecisionContext.LOCAL_ACTIVITY_MARKER_NAME)) {
        localActivityMarkers.add(event);
      }
    }

    if (localActivityMarkers.isEmpty()) {
      return false;
    }

    int processed = 0;
    while (context.numPendingLaTasks() > 0) {
      int numTasks = context.numPendingLaTasks();
      for (HistoryEvent event : localActivityMarkers) {
        processEvent(event);
      }

      eventLoop();

      processed += numTasks;
      if (processed == localActivityMarkers.size()) {
        return false;
      }
    }
    return false;
  }

  // Return whether we would need a new workflow task immediately.
  private boolean executeLocalActivities(long startTime, int decisionTimeoutSecs) {
    Duration maxProcessingTime = Duration.ofSeconds((long) (0.8 * decisionTimeoutSecs));

    while (context.numPendingLaTasks() > 0) {
      Duration processingTime = Duration.ofMillis(System.currentTimeMillis() - startTime);
      Duration maxWaitAllowed = maxProcessingTime.minus(processingTime);

      boolean started = context.startUnstartedLaTasks(maxWaitAllowed);
      if (!started) {
        // We were not able to send the current batch of la tasks before deadline.
        // Return true to indicate that we need a new workflow task immediately.
        return true;
      }

      try {
        context.awaitTaskCompletion(maxWaitAllowed);
      } catch (InterruptedException e) {
        return true;
      }

      eventLoop();

      if (context.numPendingLaTasks() == 0) {
        return false;
      }

      // Break local activity processing loop if we almost reach workflow task timeout.
      processingTime = Duration.ofMillis(System.currentTimeMillis() - startTime);
      if (processingTime.compareTo(maxProcessingTime) > 0) {
        return true;
      }
    }
    return false;
  }

  int getWorkflowTaskTimeoutSeconds() {
    return startedEvent.getWorkflowTaskTimeoutSeconds();
  }

  @Override
  public void close() {
    lock.lock();
    try {
      workflow.close();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Optional<Payloads> query(
      PollWorkflowTaskQueueResponseOrBuilder response, WorkflowQuery query) throws Throwable {
    lock.lock();
    try {
      AtomicReference<Optional<Payloads>> result = new AtomicReference<>();
      decideImpl(response, () -> result.set(workflow.query(query)));
      return result.get();
    } finally {
      lock.unlock();
    }
  }

  public Consumer<HistoryEvent> getLocalActivityCompletionSink() {
    return localActivityCompletionSink;
  }

  private class WorkflowTaskWithHistoryIteratorImpl implements WorkflowTaskWithHistoryIterator {

    private final Duration retryServiceOperationInitialInterval = Duration.ofMillis(200);
    private final Duration retryServiceOperationMaxInterval = Duration.ofSeconds(4);
    private final Duration paginationStart = Duration.ofMillis(System.currentTimeMillis());
    private Duration workflowTaskTimeout;

    private final PollWorkflowTaskQueueResponseOrBuilder task;
    private Iterator<HistoryEvent> current;
    private ByteString nextPageToken;

    WorkflowTaskWithHistoryIteratorImpl(
        PollWorkflowTaskQueueResponseOrBuilder task, Duration workflowTaskTimeout) {
      this.task = Objects.requireNonNull(task);
      this.workflowTaskTimeout = Objects.requireNonNull(workflowTaskTimeout);

      History history = task.getHistory();
      current = history.getEventsList().iterator();
      nextPageToken = task.getNextPageToken();
    }

    @Override
    public PollWorkflowTaskQueueResponseOrBuilder getWorkflowTask() {
      lock.lock();
      try {
        return task;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public Iterator<HistoryEvent> getHistory() {
      return new Iterator<HistoryEvent>() {
        @Override
        public boolean hasNext() {
          return current.hasNext() || !nextPageToken.isEmpty();
        }

        @Override
        public HistoryEvent next() {
          if (current.hasNext()) {
            return current.next();
          }

          Duration passed = Duration.ofMillis(System.currentTimeMillis()).minus(paginationStart);
          Duration expiration = workflowTaskTimeout.minus(passed);
          if (expiration.isZero() || expiration.isNegative()) {
            throw Status.DEADLINE_EXCEEDED
                .withDescription(
                    "getWorkflowExecutionHistory pagination took longer than workflow task timeout")
                .asRuntimeException();
          }
          RpcRetryOptions retryOptions =
              RpcRetryOptions.newBuilder()
                  .setExpiration(expiration)
                  .setInitialInterval(retryServiceOperationInitialInterval)
                  .setMaximumInterval(retryServiceOperationMaxInterval)
                  .build();

          GetWorkflowExecutionHistoryRequest request =
              GetWorkflowExecutionHistoryRequest.newBuilder()
                  .setNamespace(context.getNamespace())
                  .setExecution(task.getWorkflowExecution())
                  .setMaximumPageSize(MAXIMUM_PAGE_SIZE)
                  .setNextPageToken(nextPageToken)
                  .build();

          try {
            GetWorkflowExecutionHistoryResponse r =
                GrpcRetryer.retryWithResult(
                    retryOptions,
                    () ->
                        service
                            .blockingStub()
                            .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                            .getWorkflowExecutionHistory(request));
            current = r.getHistory().getEventsList().iterator();
            nextPageToken = r.getNextPageToken();
          } catch (Exception e) {
            throw new Error(e);
          }
          return current.next();
        }
      };
    }
  }
}
