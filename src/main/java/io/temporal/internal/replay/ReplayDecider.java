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

package io.temporal.internal.replay;

import static io.temporal.worker.NonDeterministicWorkflowPolicy.FailWorkflow;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import io.grpc.StatusRuntimeException;
import io.temporal.EventType;
import io.temporal.GetWorkflowExecutionHistoryRequest;
import io.temporal.GetWorkflowExecutionHistoryResponse;
import io.temporal.History;
import io.temporal.HistoryEvent;
import io.temporal.PollForDecisionTaskResponse;
import io.temporal.TimerFiredEventAttributes;
import io.temporal.WorkflowExecutionSignaledEventAttributes;
import io.temporal.WorkflowExecutionStartedEventAttributes;
import io.temporal.WorkflowQuery;
import io.temporal.common.RetryOptions;
import io.temporal.internal.common.OptionsUtils;
import io.temporal.internal.common.Retryer;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.HistoryHelper.DecisionEvents;
import io.temporal.internal.replay.HistoryHelper.DecisionEventsIterator;
import io.temporal.internal.worker.DecisionTaskWithHistoryIterator;
import io.temporal.internal.worker.LocalActivityWorker;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.serviceclient.GrpcWorkflowServiceFactory;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Implements decider that relies on replay of a workflow code. An instance of this class is created
 * per decision.
 */
class ReplayDecider implements Decider, Consumer<HistoryEvent> {

  private static final int MAXIMUM_PAGE_SIZE = 10000;

  private final DecisionsHelper decisionsHelper;
  private final DecisionContextImpl context;
  private final GrpcWorkflowServiceFactory service;
  private final ReplayWorkflow workflow;
  private boolean cancelRequested;
  private boolean completed;
  private WorkflowExecutionException failure;
  private long wakeUpTime;
  private Consumer<Exception> timerCancellationHandler;
  private final Scope metricsScope;
  private final long wfStartTimeNanos;
  private final WorkflowExecutionStartedEventAttributes startedEvent;

  ReplayDecider(
      GrpcWorkflowServiceFactory service,
      String domain,
      ReplayWorkflow workflow,
      DecisionsHelper decisionsHelper,
      SingleWorkerOptions options,
      BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller) {
    this.service = service;
    this.workflow = workflow;
    this.decisionsHelper = decisionsHelper;
    this.metricsScope = options.getMetricsScope();
    PollForDecisionTaskResponse decisionTask = decisionsHelper.getTask();

    startedEvent =
        decisionTask.getHistory().getEvents(0).getWorkflowExecutionStartedEventAttributes();
    if (startedEvent == null) {
      throw new IllegalArgumentException(
          "First event in the history is not WorkflowExecutionStarted");
    }
    wfStartTimeNanos = decisionTask.getHistory().getEvents(0).getTimestamp();

    context =
        new DecisionContextImpl(
            decisionsHelper, domain, decisionTask, startedEvent, options, laTaskPoller, this);
  }

  private void handleWorkflowExecutionStarted(HistoryEvent event) {
    workflow.start(event, context);
  }

  private void processEvent(HistoryEvent event) {
    EventType eventType = event.getEventType();
    switch (eventType) {
      case EventTypeActivityTaskCanceled:
        context.handleActivityTaskCanceled(event);
        break;
      case EventTypeActivityTaskCompleted:
        context.handleActivityTaskCompleted(event);
        break;
      case EventTypeActivityTaskFailed:
        context.handleActivityTaskFailed(event);
        break;
      case EventTypeActivityTaskStarted:
        decisionsHelper.handleActivityTaskStarted(event);
        break;
      case EventTypeActivityTaskTimedOut:
        context.handleActivityTaskTimedOut(event);
        break;
      case EventTypeExternalWorkflowExecutionCancelRequested:
        context.handleChildWorkflowExecutionCancelRequested(event);
        decisionsHelper.handleExternalWorkflowExecutionCancelRequested(event);
        break;
      case EventTypeChildWorkflowExecutionCanceled:
        context.handleChildWorkflowExecutionCanceled(event);
        break;
      case EventTypeChildWorkflowExecutionCompleted:
        context.handleChildWorkflowExecutionCompleted(event);
        break;
      case EventTypeChildWorkflowExecutionFailed:
        context.handleChildWorkflowExecutionFailed(event);
        break;
      case EventTypeChildWorkflowExecutionStarted:
        context.handleChildWorkflowExecutionStarted(event);
        break;
      case EventTypeChildWorkflowExecutionTerminated:
        context.handleChildWorkflowExecutionTerminated(event);
        break;
      case EventTypeChildWorkflowExecutionTimedOut:
        context.handleChildWorkflowExecutionTimedOut(event);
        break;
      case EventTypeDecisionTaskCompleted:
        // NOOP
        break;
      case EventTypeDecisionTaskScheduled:
        // NOOP
        break;
      case EventTypeDecisionTaskStarted:
        throw new IllegalArgumentException("not expected");
      case EventTypeDecisionTaskTimedOut:
        // Handled in the processEvent(event)
        break;
      case EventTypeExternalWorkflowExecutionSignaled:
        context.handleExternalWorkflowExecutionSignaled(event);
        break;
      case EventTypeStartChildWorkflowExecutionFailed:
        context.handleStartChildWorkflowExecutionFailed(event);
        break;
      case EventTypeTimerFired:
        handleTimerFired(event);
        break;
      case EventTypeWorkflowExecutionCancelRequested:
        handleWorkflowExecutionCancelRequested(event);
        break;
      case EventTypeWorkflowExecutionSignaled:
        handleWorkflowExecutionSignaled(event);
        break;
      case EventTypeWorkflowExecutionStarted:
        handleWorkflowExecutionStarted(event);
        break;
      case EventTypeWorkflowExecutionTerminated:
        // NOOP
        break;
      case EventTypeWorkflowExecutionTimedOut:
        // NOOP
        break;
      case EventTypeActivityTaskScheduled:
        decisionsHelper.handleActivityTaskScheduled(event);
        break;
      case EventTypeActivityTaskCancelRequested:
        decisionsHelper.handleActivityTaskCancelRequested(event);
        break;
      case EventTypeRequestCancelActivityTaskFailed:
        decisionsHelper.handleRequestCancelActivityTaskFailed(event);
        break;
      case EventTypeMarkerRecorded:
        context.handleMarkerRecorded(event);
        break;
      case EventTypeWorkflowExecutionCompleted:
        break;
      case EventTypeWorkflowExecutionFailed:
        break;
      case EventTypeWorkflowExecutionCanceled:
        break;
      case EventTypeWorkflowExecutionContinuedAsNew:
        break;
      case EventTypeTimerStarted:
        decisionsHelper.handleTimerStarted(event);
        break;
      case EventTypeTimerCanceled:
        context.handleTimerCanceled(event);
        break;
      case EventTypeSignalExternalWorkflowExecutionInitiated:
        decisionsHelper.handleSignalExternalWorkflowExecutionInitiated(event);
        break;
      case EventTypeSignalExternalWorkflowExecutionFailed:
        context.handleSignalExternalWorkflowExecutionFailed(event);
        break;
      case EventTypeRequestCancelExternalWorkflowExecutionInitiated:
        decisionsHelper.handleRequestCancelExternalWorkflowExecutionInitiated(event);
        break;
      case EventTypeRequestCancelExternalWorkflowExecutionFailed:
        decisionsHelper.handleRequestCancelExternalWorkflowExecutionFailed(event);
        break;
      case EventTypeStartChildWorkflowExecutionInitiated:
        decisionsHelper.handleStartChildWorkflowExecutionInitiated(event);
        break;
      case EventTypeCancelTimerFailed:
        decisionsHelper.handleCancelTimerFailed(event);
        break;
      case EventTypeDecisionTaskFailed:
        context.handleDecisionTaskFailed(event);
        break;
      case EventTypeUpsertWorkflowSearchAttributes:
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
    } catch (CancellationException e) {
      if (!cancelRequested) {
        failure = workflow.mapUnexpectedException(e);
      }
      completed = true;
    } catch (Throwable e) {
      // can cast as Error is caught above.
      failure = workflow.mapUnexpectedException((Exception) e);
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
      ContinueAsNewWorkflowExecutionParameters continueAsNewOnCompletion =
          context.getContinueAsNewOnCompletion();
      if (continueAsNewOnCompletion != null) {
        decisionsHelper.continueAsNewWorkflowExecution(continueAsNewOnCompletion);
        metricsScope.counter(MetricsType.WORKFLOW_CONTINUE_AS_NEW_COUNTER).inc(1);
      } else {
        byte[] workflowOutput = workflow.getOutput();
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
    long delaySeconds =
        OptionsUtils.roundUpToSeconds(Duration.ofMillis(delayMilliseconds)).getSeconds();
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
    assert (event.getEventType() == EventType.EventTypeWorkflowExecutionSignaled);
    final WorkflowExecutionSignaledEventAttributes signalAttributes =
        event.getWorkflowExecutionSignaledEventAttributes();
    if (completed) {
      throw new IllegalStateException("Signal received after workflow is closed.");
    }
    this.workflow.handleSignal(
        signalAttributes.getSignalName(),
        signalAttributes.getInput().toByteArray(),
        event.getEventId());
  }

  @Override
  public DecisionResult decide(PollForDecisionTaskResponse decisionTask) throws Throwable {
    boolean forceCreateNewDecisionTask = decideImpl(decisionTask, null);
    return new DecisionResult(decisionsHelper.getDecisions(), forceCreateNewDecisionTask);
  }

  // Returns boolean to indicate whether we need to force create new decision task for local
  // activity heartbeating.
  private boolean decideImpl(PollForDecisionTaskResponse decisionTask, Functions.Proc query)
      throws Throwable {
    boolean forceCreateNewDecisionTask = false;
    try {
      long startTime = System.currentTimeMillis();
      DecisionTaskWithHistoryIterator decisionTaskWithHistoryIterator =
          new DecisionTaskWithHistoryIteratorImpl(
              decisionTask, Duration.ofSeconds(startedEvent.getTaskStartToCloseTimeoutSeconds()));
      HistoryHelper historyHelper =
          new HistoryHelper(
              decisionTaskWithHistoryIterator, context.getReplayCurrentTimeMilliseconds());
      DecisionEventsIterator iterator = historyHelper.getIterator();
      if ((decisionsHelper.getNextDecisionEventId()
              != historyHelper.getPreviousStartedEventId()
                  + 2) // getNextDecisionEventId() skips over completed.
          && (decisionsHelper.getNextDecisionEventId() != 0
              && historyHelper.getPreviousStartedEventId() != 0)
          && (decisionTask.getHistory().getEventsCount() > 0)) {
        throw new IllegalStateException(
            String.format(
                "ReplayDecider expects next event id at %d. History's previous started event id is %d",
                decisionsHelper.getNextDecisionEventId(),
                historyHelper.getPreviousStartedEventId()));
      }

      while (iterator.hasNext()) {
        DecisionEvents decision = iterator.next();
        context.setReplaying(decision.isReplay());
        context.setReplayCurrentTimeMilliseconds(decision.getReplayCurrentTimeMilliseconds());

        decisionsHelper.handleDecisionTaskStartedEvent(decision);
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

        forceCreateNewDecisionTask =
            processEventLoop(
                startTime,
                startedEvent.getTaskStartToCloseTimeoutSeconds(),
                decision,
                decisionTask.getQuery() != null);

        mayBeCompleteWorkflow();
        if (decision.isReplay()) {
          decisionsHelper.notifyDecisionSent();
        }
        // Updates state machines with results of the previous decisions
        for (HistoryEvent event : decision.getDecisionEvents()) {
          processEvent(event);
        }
        // Reset state to before running the event loop
        decisionsHelper.handleDecisionTaskStartedEvent(decision);
      }

      return forceCreateNewDecisionTask;
    } catch (Error e) {
      if (this.workflow.getWorkflowImplementationOptions().getNonDeterministicWorkflowPolicy()
          == FailWorkflow) {
        // fail workflow
        failure = workflow.mapError(e);
        completed = true;
        completeWorkflow();
        return false;
      } else {
        metricsScope.counter(MetricsType.DECISION_TASK_ERROR_COUNTER).inc(1);
        // fail decision, not a workflow
        throw e;
      }
    } finally {
      if (query != null) {
        query.apply();
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

  // Return whether we would need a new decision task immediately.
  private boolean executeLocalActivities(long startTime, int decisionTimeoutSecs) {
    Duration maxProcessingTime = Duration.ofSeconds((long) (0.8 * decisionTimeoutSecs));

    while (context.numPendingLaTasks() > 0) {
      Duration processingTime = Duration.ofMillis(System.currentTimeMillis() - startTime);
      Duration maxWaitAllowed = maxProcessingTime.minus(processingTime);

      boolean started = context.startUnstartedLaTasks(maxWaitAllowed);
      if (!started) {
        // We were not able to send the current batch of la tasks before deadline.
        // Return true to indicate that we need a new decision task immediately.
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

      // Break local activity processing loop if we almost reach decision task timeout.
      processingTime = Duration.ofMillis(System.currentTimeMillis() - startTime);
      if (processingTime.compareTo(maxProcessingTime) > 0) {
        return true;
      }
    }
    return false;
  }

  int getDecisionTimeoutSeconds() {
    return startedEvent.getTaskStartToCloseTimeoutSeconds();
  }

  @Override
  public void close() {
    workflow.close();
  }

  @Override
  public byte[] query(PollForDecisionTaskResponse response, WorkflowQuery query) throws Throwable {
    AtomicReference<byte[]> result = new AtomicReference<>();
    decideImpl(response, () -> result.set(workflow.query(query)));
    return result.get();
  }

  @Override
  public void accept(HistoryEvent event) {
    processEvent(event);
  }

  private class DecisionTaskWithHistoryIteratorImpl implements DecisionTaskWithHistoryIterator {

    private final Duration retryServiceOperationInitialInterval = Duration.ofMillis(200);
    private final Duration retryServiceOperationMaxInterval = Duration.ofSeconds(4);
    private final Duration paginationStart = Duration.ofMillis(System.currentTimeMillis());
    private Duration decisionTaskStartToCloseTimeout;

    private final Duration retryServiceOperationExpirationInterval() {
      Duration passed = Duration.ofMillis(System.currentTimeMillis()).minus(paginationStart);
      return decisionTaskStartToCloseTimeout.minus(passed);
    }

    private final PollForDecisionTaskResponse task;
    private Iterator<HistoryEvent> current;
    private ByteString nextPageToken;

    DecisionTaskWithHistoryIteratorImpl(
        PollForDecisionTaskResponse task, Duration decisionTaskStartToCloseTimeout) {
      this.task = Objects.requireNonNull(task);
      this.decisionTaskStartToCloseTimeout =
          Objects.requireNonNull(decisionTaskStartToCloseTimeout);

      History history = task.getHistory();
      current = history.getEventsList().iterator();
      nextPageToken = task.getNextPageToken();
    }

    @Override
    public PollForDecisionTaskResponse getDecisionTask() {
      return task;
    }

    @Override
    public Iterator<HistoryEvent> getHistory() {
      return new Iterator<HistoryEvent>() {
        @Override
        public boolean hasNext() {
          return current.hasNext() || nextPageToken != null;
        }

        @Override
        public HistoryEvent next() {
          if (current.hasNext()) {
            return current.next();
          }

          metricsScope.counter(MetricsType.WORKFLOW_GET_HISTORY_COUNTER).inc(1);
          Stopwatch sw = metricsScope.timer(MetricsType.WORKFLOW_GET_HISTORY_LATENCY).start();
          RetryOptions retryOptions =
              new RetryOptions.Builder()
                  .setExpiration(retryServiceOperationExpirationInterval())
                  .setInitialInterval(retryServiceOperationInitialInterval)
                  .setMaximumInterval(retryServiceOperationMaxInterval)
                  .build();

          GetWorkflowExecutionHistoryRequest request =
              GetWorkflowExecutionHistoryRequest.newBuilder()
                  .setDomain(context.getDomain())
                  .setExecution(task.getWorkflowExecution())
                  .setMaximumPageSize(MAXIMUM_PAGE_SIZE)
                  .setNextPageToken(nextPageToken)
                  .build();

          try {
            GetWorkflowExecutionHistoryResponse r =
                Retryer.retryWithResult(
                    retryOptions,
                    () -> service.blockingStub().getWorkflowExecutionHistory(request));
            current = r.getHistory().getEventsList().iterator();
            nextPageToken = r.getNextPageToken();
            metricsScope.counter(MetricsType.WORKFLOW_GET_HISTORY_SUCCEED_COUNTER).inc(1);
            sw.stop();
          } catch (StatusRuntimeException e) {
            metricsScope.counter(MetricsType.WORKFLOW_GET_HISTORY_FAILED_COUNTER).inc(1);
            throw new Error(e);
          }
          return current.next();
        }
      };
    }
  }
}
