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
package com.uber.cadence.worker;

import com.uber.cadence.AsyncDecisionContext;
import com.uber.cadence.generic.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.TimerFiredEventAttributes;
import com.uber.cadence.TimerStartedEventAttributes;
import com.uber.cadence.WorkflowExecutionSignaledEventAttributes;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

class AsyncDecider {

    private static final Log log = LogFactory.getLog(AsyncDecider.class);

    private static final int MILLION = 1000000;

    private final HistoryHelper historyHelper;

    private final DecisionsHelper decisionsHelper;

    private final GenericAsyncActivityClientImpl activityClient;

    private final GenericAsyncWorkflowClientImpl workflowClient;

    private final AsyncWorkflowClockImpl workflowClock;

    private final AsyncDecisionContext context;

    private AsyncWorkflow workflow;

    private boolean cancelRequested;

    private WorkfowContextImpl workflowContext;

    private boolean unhandledDecision;

    private boolean completed;

    private Throwable failure;

    public AsyncDecider(AsyncWorkflow workflow, HistoryHelper historyHelper, DecisionsHelper decisionsHelper) throws Exception {
        this.workflow = workflow;
        this.historyHelper = historyHelper;
        this.decisionsHelper = decisionsHelper;
        this.activityClient = new GenericAsyncActivityClientImpl(decisionsHelper);
        PollForDecisionTaskResponse decisionTask = historyHelper.getDecisionTask();
        workflowContext = new WorkfowContextImpl(decisionTask);
        this.workflowClient = new GenericAsyncWorkflowClientImpl(decisionsHelper, workflowContext);
        this.workflowClock = new AsyncWorkflowClockImpl(decisionsHelper);
        context = new AsyncDecisionContextImpl(activityClient, workflowClient, workflowClock, workflowContext);
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    private void handleWorkflowExecutionStarted(HistoryEvent event) throws Exception {
        workflow.start(event, context);
    }

    private void processEvent(HistoryEvent event, EventType eventType) throws Throwable {
        switch (eventType) {
            case ActivityTaskCanceled:
                activityClient.handleActivityTaskCanceled(event);
                break;
            case ActivityTaskCompleted:
                activityClient.handleActivityTaskCompleted(event);
                break;
            case ActivityTaskFailed:
                activityClient.handleActivityTaskFailed(event);
                break;
            case ActivityTaskStarted:
                activityClient.handleActivityTaskStarted(event.getActivityTaskStartedEventAttributes());
                break;
            case ActivityTaskTimedOut:
                activityClient.handleActivityTaskTimedOut(event);
                break;
            case ExternalWorkflowExecutionCancelRequested:
                workflowClient.handleChildWorkflowExecutionCancelRequested(event);
                break;
            case ChildWorkflowExecutionCanceled:
                workflowClient.handleChildWorkflowExecutionCanceled(event);
                break;
            case ChildWorkflowExecutionCompleted:
                workflowClient.handleChildWorkflowExecutionCompleted(event);
                break;
            case ChildWorkflowExecutionFailed:
                workflowClient.handleChildWorkflowExecutionFailed(event);
                break;
            case ChildWorkflowExecutionStarted:
                workflowClient.handleChildWorkflowExecutionStarted(event);
                break;
            case ChildWorkflowExecutionTerminated:
                workflowClient.handleChildWorkflowExecutionTerminated(event);
                break;
            case ChildWorkflowExecutionTimedOut:
                workflowClient.handleChildWorkflowExecutionTimedOut(event);
                break;
            case DecisionTaskCompleted:
                handleDecisionTaskCompleted(event);
                break;
            case DecisionTaskScheduled:
                // NOOP
                break;
            case DecisionTaskStarted:
                handleDecisionTaskStarted(event);
                break;
            case DecisionTaskTimedOut:
                // Handled in the processEvent(event)
                break;
//        case ExternalWorkflowExecutionSignaled:
//            workflowClient.handleExternalWorkflowExecutionSignaled(event);
//            break;
            case StartChildWorkflowExecutionFailed:
                workflowClient.handleStartChildWorkflowExecutionFailed(event);
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
                handleTimerStarted(event);
                break;
            case TimerCanceled:
                workflowClock.handleTimerCanceled(event);
                break;
//        case SignalExternalWorkflowExecutionInitiated:
//            decisionsHelper.handleSignalExternalWorkflowExecutionInitiated(event);
//            break;
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
        }
    }

    private void eventLoop() throws Throwable {
        if (completed) {
            return;
        }
        try {
            completed = workflow.eventLoop();
        } catch (CancellationException e) {
            if (!cancelRequested) {
                failure = e;
            }
            completed = true;
        } catch (Throwable e) {
            failure = e;
            completed = true;
        }
    }

    private void completeWorkflow() {
        if (completed && !unhandledDecision) {
            if (failure != null) {
                decisionsHelper.failWorkflowExecution(failure);
            } else if (cancelRequested) {
                decisionsHelper.cancelWorkflowExecution();
            } else {
                ContinueAsNewWorkflowExecutionParameters continueAsNewOnCompletion = workflowContext.getContinueAsNewOnCompletion();
                if (continueAsNewOnCompletion != null) {
                    decisionsHelper.continueAsNewWorkflowExecution(continueAsNewOnCompletion);
                } else {
                    byte[] workflowOutput = workflow.getOutput();
                    decisionsHelper.completeWorkflowExecution(workflowOutput);
                }
            }
        } else {
            long nextWakeUpTime = workflow.getNextWakeUpTime();
            if (nextWakeUpTime == 0) { // No time based waiting
                workflowClock.cancelAllTimers();
            }
            long delayMilliseconds = nextWakeUpTime - workflowClock.currentTimeMillis();
            if (nextWakeUpTime > workflowClock.currentTimeMillis()) {
                long delaySeconds = TimeUnit.MILLISECONDS.toSeconds(delayMilliseconds);
                if (delaySeconds == 0) {
                    delaySeconds = 1; //TODO: Deal with subsecond delays.
                }
                workflowClock.createTimer(delaySeconds, (t) -> {
                    // Intentionally left empty.
                    // Timer ensures that decision is scheduled at the time workflow can make progress.
                    // But no specific timer related action is necessary.
                });
            }
        }
    }

    private void handleDecisionTaskStarted(HistoryEvent event) throws Throwable {
    }

    private void handleWorkflowExecutionCancelRequested(HistoryEvent event) throws Throwable {
        workflowContext.setCancelRequested(true);
        workflow.cancel(new CancellationException());
        cancelRequested = true;
    }

    private void handleTimerFired(HistoryEvent event) throws Throwable {
        TimerFiredEventAttributes attributes = event.getTimerFiredEventAttributes();
        String timerId = attributes.getTimerId();
        if (timerId.equals(DecisionsHelper.FORCE_IMMEDIATE_DECISION_TIMER)) {
            return;
        }
        workflowClock.handleTimerFired(event.getEventId(), attributes);
    }

    private void handleTimerStarted(HistoryEvent event) {
        TimerStartedEventAttributes attributes = event.getTimerStartedEventAttributes();
        String timerId = attributes.getTimerId();
        if (timerId.equals(DecisionsHelper.FORCE_IMMEDIATE_DECISION_TIMER)) {
            return;
        }
        decisionsHelper.handleTimerStarted(event);
    }

    private void handleWorkflowExecutionSignaled(HistoryEvent event) throws Throwable {
        assert (event.getEventType() == EventType.WorkflowExecutionSignaled);
        final WorkflowExecutionSignaledEventAttributes signalAttributes = event.getWorkflowExecutionSignaledEventAttributes();
        if (completed) {
            throw new IllegalStateException("Signal received after workflow is closed. TODO: Change signal handling from callback to a queue to fix the issue.");
        }
        this.workflow.processSignal(signalAttributes.getSignalName(), signalAttributes.getInput());
    }

    private void handleDecisionTaskCompleted(HistoryEvent event) {
        decisionsHelper.handleDecisionCompletion(event.getDecisionTaskCompletedEventAttributes());
    }

    // TODO: Simplify as Cadence reorders concurrent decisions on the server.
    public void decide() throws Exception {
        try {
            long lastNonReplayedEventId = historyHelper.getLastNonReplayEventId();
            // Buffer events until the next DecisionTaskStarted and then process them
            // setting current time to the time of DecisionTaskStarted event
            HistoryHelper.EventsIterator eventsIterator = historyHelper.getEvents();
            List<HistoryEvent> reordered = null;
            do {
                List<HistoryEvent> decisionCompletionToStartEvents = new ArrayList<HistoryEvent>();
                while (eventsIterator.hasNext()) {
                    HistoryEvent event = eventsIterator.next();
                    EventType eventType = event.getEventType();
                    if (eventType == EventType.DecisionTaskCompleted) {
                        decisionsHelper.setWorkflowContextData(event.getDecisionTaskCompletedEventAttributes().getExecutionContext());
                    } else if (eventType == EventType.DecisionTaskStarted) {
                        decisionsHelper.handleDecisionTaskStartedEvent();

                        if (!eventsIterator.isNextDecisionFailed()) {
                            // Cadence timestamp is in nanoseconds
                            long replayCurrentTimeMilliseconds = event.getTimestamp() / MILLION;
                            workflowClock.setReplayCurrentTimeMilliseconds(replayCurrentTimeMilliseconds);
                            break;
                        }
                    } else if (eventType == EventType.DecisionTaskScheduled
                            || eventType == EventType.DecisionTaskTimedOut
                            || eventType == EventType.DecisionTaskFailed) {
                        // skip
                    } else {
                        decisionCompletionToStartEvents.add(event);
                    }
                }
                for (HistoryEvent event : decisionCompletionToStartEvents) {
                    if (event.getEventId() >= lastNonReplayedEventId) {
                        workflowClock.setReplaying(false);
                    }
                    EventType eventType = event.getEventType();
                    processEvent(event, eventType);
                }
                eventLoop();
                completeWorkflow();
            }
            while (eventsIterator.hasNext());
            if (unhandledDecision) {
                unhandledDecision = false;
                completeWorkflow();
            }
        }
        //TODO (Cadence): Handle Cadence exception gracefully.
//        catch (AmazonServiceException e) {
//            // We don't want to fail workflow on service exceptions like 500 or throttling
//            // Throwing from here drops decision task which is OK as it is rescheduled after its StartToClose timeout.
//            if (e.getErrorType() == ErrorType.Client && !"ThrottlingException".equals(e.getErrorCode())) {
//                if (log.isErrorEnabled()) {
//                    log.error("Failing workflow " + workflowContext.getWorkflowExecution(), e);
//                }
//                decisionsHelper.failWorkflowDueToUnexpectedError(e);
//            }
//            else {
//                throw e;
//            }
//        }
        catch (Throwable e) {
            if (log.isErrorEnabled()) {
                log.error("Failing workflow " + workflowContext.getWorkflowExecution(), e);
            }
            decisionsHelper.failWorkflowDueToUnexpectedError(e);
        }
    }

    public String getAsynchronousThreadDumpAsString() {
        checkAsynchronousThreadDumpState();
        return workflow.getAsynchronousThreadDump();
    }

    private void checkAsynchronousThreadDumpState() {
        if (workflow == null) {
            throw new IllegalStateException("workflow hasn't started yet");
        }
        if (decisionsHelper.isWorkflowFailed()) {
            throw new IllegalStateException("Cannot get AsynchronousThreadDump of a failed workflow",
                    decisionsHelper.getWorkflowFailureCause());
        }
    }

    public DecisionsHelper getDecisionsHelper() {
        return decisionsHelper;
    }

}
