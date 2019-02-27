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

package com.uber.cadence.internal.replay;

import static com.uber.cadence.worker.NonDeterministicWorkflowPolicy.FailWorkflow;

import com.uber.cadence.Decision;
import com.uber.cadence.EventType;
import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.GetWorkflowExecutionHistoryResponse;
import com.uber.cadence.History;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.TimerFiredEventAttributes;
import com.uber.cadence.WorkflowExecutionSignaledEventAttributes;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.internal.common.OptionsUtils;
import com.uber.cadence.internal.common.Retryer;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.internal.replay.HistoryHelper.DecisionEvents;
import com.uber.cadence.internal.replay.HistoryHelper.DecisionEventsIterator;
import com.uber.cadence.internal.worker.DecisionTaskWithHistoryIterator;
import com.uber.cadence.internal.worker.WorkflowExecutionException;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.workflow.Functions;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.thrift.TException;

/**
 * Implements decider that relies on replay of a workflow code. An instance of this class is created
 * per decision.
 */
class ReplayDecider implements Decider {

  private static final int MAXIMUM_PAGE_SIZE = 10000;

  private final DecisionsHelper decisionsHelper;

  private final DecisionContextImpl context;

  private IWorkflowService service;
  private ReplayWorkflow workflow;

  private boolean cancelRequested;

  private boolean completed;

  private WorkflowExecutionException failure;

  private long wakeUpTime;

  private Consumer<Exception> timerCancellationHandler;

  private final Scope metricsScope;

  private long wfStartTime = -1;

  private final WorkflowExecutionStartedEventAttributes startedEvent;

  ReplayDecider(
      IWorkflowService service,
      String domain,
      ReplayWorkflow workflow,
      DecisionsHelper decisionsHelper,
      Scope metricsScope,
      boolean enableLoggingInReplay) {
    this.service = service;
    this.workflow = workflow;
    this.decisionsHelper = decisionsHelper;
    this.metricsScope = metricsScope;
    PollForDecisionTaskResponse decisionTask = decisionsHelper.getTask();

    startedEvent =
        decisionTask.getHistory().getEvents().get(0).getWorkflowExecutionStartedEventAttributes();
    if (startedEvent == null) {
      throw new IllegalArgumentException(
          "First event in the history is not WorkflowExecutionStarted");
    }

    context =
        new DecisionContextImpl(
            decisionsHelper, domain, decisionTask, startedEvent, enableLoggingInReplay);
    context.setMetricsScope(metricsScope);
  }

  private void handleWorkflowExecutionStarted(HistoryEvent event) throws Exception {
    workflow.start(event, context);
  }

  private void processEvent(HistoryEvent event) throws Throwable {
    EventType eventType = event.getEventType();
    switch (eventType) {
      case ActivityTaskCanceled:
        context.handleActivityTaskCanceled(event);
        break;
      case ActivityTaskCompleted:
        context.handleActivityTaskCompleted(event);
        break;
      case ActivityTaskFailed:
        context.handleActivityTaskFailed(event);
        break;
      case ActivityTaskStarted:
        decisionsHelper.handleActivityTaskStarted(event);
        break;
      case ActivityTaskTimedOut:
        context.handleActivityTaskTimedOut(event);
        break;
      case ExternalWorkflowExecutionCancelRequested:
        context.handleChildWorkflowExecutionCancelRequested(event);
        decisionsHelper.handleExternalWorkflowExecutionCancelRequested(event);
        break;
      case ChildWorkflowExecutionCanceled:
        context.handleChildWorkflowExecutionCanceled(event);
        break;
      case ChildWorkflowExecutionCompleted:
        context.handleChildWorkflowExecutionCompleted(event);
        break;
      case ChildWorkflowExecutionFailed:
        context.handleChildWorkflowExecutionFailed(event);
        break;
      case ChildWorkflowExecutionStarted:
        context.handleChildWorkflowExecutionStarted(event);
        break;
      case ChildWorkflowExecutionTerminated:
        context.handleChildWorkflowExecutionTerminated(event);
        break;
      case ChildWorkflowExecutionTimedOut:
        context.handleChildWorkflowExecutionTimedOut(event);
        break;
      case DecisionTaskCompleted:
        // NOOP
        break;
      case DecisionTaskScheduled:
        // NOOP
        break;
      case DecisionTaskStarted:
        throw new IllegalArgumentException("not expected");
      case DecisionTaskTimedOut:
        // Handled in the processEvent(event)
        break;
      case ExternalWorkflowExecutionSignaled:
        context.handleExternalWorkflowExecutionSignaled(event);
        break;
      case StartChildWorkflowExecutionFailed:
        context.handleStartChildWorkflowExecutionFailed(event);
        break;
      case TimerFired:
        handleTimerFired(event);
        break;
      case WorkflowExecutionCancelRequested:
        handleWorkflowExecutionCancelRequested(event);
        break;
      case WorkflowExecutionSignaled:
        handleWorkflowExecutionSignaled(event);
        break;
      case WorkflowExecutionStarted:
        handleWorkflowExecutionStarted(event);
        break;
      case WorkflowExecutionTerminated:
        // NOOP
        break;
      case WorkflowExecutionTimedOut:
        // NOOP
        break;
      case ActivityTaskScheduled:
        decisionsHelper.handleActivityTaskScheduled(event);
        break;
      case ActivityTaskCancelRequested:
        decisionsHelper.handleActivityTaskCancelRequested(event);
        break;
      case RequestCancelActivityTaskFailed:
        decisionsHelper.handleRequestCancelActivityTaskFailed(event);
        break;
      case MarkerRecorded:
        context.handleMarkerRecorded(event);
        break;
      case WorkflowExecutionCompleted:
        break;
      case WorkflowExecutionFailed:
        break;
      case WorkflowExecutionCanceled:
        break;
      case WorkflowExecutionContinuedAsNew:
        break;
      case TimerStarted:
        decisionsHelper.handleTimerStarted(event);
        break;
      case TimerCanceled:
        context.handleTimerCanceled(event);
        break;
      case SignalExternalWorkflowExecutionInitiated:
        decisionsHelper.handleSignalExternalWorkflowExecutionInitiated(event);
        break;
      case SignalExternalWorkflowExecutionFailed:
        context.handleSignalExternalWorkflowExecutionFailed(event);
        break;
      case RequestCancelExternalWorkflowExecutionInitiated:
        decisionsHelper.handleRequestCancelExternalWorkflowExecutionInitiated(event);
        break;
      case RequestCancelExternalWorkflowExecutionFailed:
        decisionsHelper.handleRequestCancelExternalWorkflowExecutionFailed(event);
        break;
      case StartChildWorkflowExecutionInitiated:
        decisionsHelper.handleStartChildWorkflowExecutionInitiated(event);
        break;
      case CancelTimerFailed:
        decisionsHelper.handleCancelTimerFailed(event);
        break;
      case DecisionTaskFailed:
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

    if (wfStartTime != -1) {
      long nanoTime =
          TimeUnit.NANOSECONDS.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
      com.uber.m3.util.Duration d = com.uber.m3.util.Duration.ofNanos(nanoTime - wfStartTime);
      metricsScope.timer(MetricsType.WORKFLOW_E2E_LATENCY).record(d);
    }
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
    assert (event.getEventType() == EventType.WorkflowExecutionSignaled);
    final WorkflowExecutionSignaledEventAttributes signalAttributes =
        event.getWorkflowExecutionSignaledEventAttributes();
    if (completed) {
      throw new IllegalStateException("Signal received after workflow is closed.");
    }
    this.workflow.handleSignal(
        signalAttributes.getSignalName(), signalAttributes.getInput(), event.getEventId());
  }

  @Override
  public List<Decision> decide(PollForDecisionTaskResponse decisionTask) throws Throwable {
    decideImpl(decisionTask, null);
    return getDecisionsHelper().getDecisions();
  }

  private void decideImpl(PollForDecisionTaskResponse decisionTask, Functions.Proc query)
      throws Throwable {
    try {
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
          && (decisionTask.getHistory().getEventsSize() > 0)) {
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
          processEvent(event);
        }
        for (HistoryEvent event : decision.getEvents()) {
          processEvent(event);
        }
        eventLoop();
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
    } catch (Error e) {
      if (this.workflow.getWorkflowImplementationOptions().getNonDeterministicWorkflowPolicy()
          == FailWorkflow) {
        // fail workflow
        failure = workflow.mapError(e);
        completed = true;
        completeWorkflow();
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

  @Override
  public void close() {
    workflow.close();
  }

  DecisionsHelper getDecisionsHelper() {
    return decisionsHelper;
  }

  @Override
  public byte[] query(PollForDecisionTaskResponse response, WorkflowQuery query) throws Throwable {
    AtomicReference<byte[]> result = new AtomicReference<>();
    decideImpl(response, () -> result.set(workflow.query(query)));
    return result.get();
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
    private byte[] nextPageToken;

    DecisionTaskWithHistoryIteratorImpl(
        PollForDecisionTaskResponse task, Duration decisionTaskStartToCloseTimeout) {
      this.task = Objects.requireNonNull(task);
      this.decisionTaskStartToCloseTimeout =
          Objects.requireNonNull(decisionTaskStartToCloseTimeout);

      History history = task.getHistory();
      current = history.getEventsIterator();
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

          GetWorkflowExecutionHistoryRequest request = new GetWorkflowExecutionHistoryRequest();
          request
              .setDomain(context.getDomain())
              .setExecution(task.getWorkflowExecution())
              .setMaximumPageSize(MAXIMUM_PAGE_SIZE)
              .setNextPageToken(nextPageToken);

          try {
            GetWorkflowExecutionHistoryResponse r =
                Retryer.retryWithResult(
                    retryOptions, () -> service.GetWorkflowExecutionHistory(request));
            current = r.getHistory().getEventsIterator();
            nextPageToken = r.getNextPageToken();
            metricsScope.counter(MetricsType.WORKFLOW_GET_HISTORY_SUCCEED_COUNTER).inc(1);
            sw.stop();
          } catch (TException e) {
            metricsScope.counter(MetricsType.WORKFLOW_GET_HISTORY_FAILED_COUNTER).inc(1);
            throw new Error(e);
          }
          return current.next();
        }
      };
    }
  }
}
