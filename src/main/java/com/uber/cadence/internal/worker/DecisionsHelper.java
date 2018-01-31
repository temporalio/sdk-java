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

import com.uber.cadence.internal.WorkflowException;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.workflow.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class DecisionsHelper {

    //    private static final Log log = LogFactory.getLog(DecisionsHelper.class);

    static final int MAXIMUM_DECISIONS_PER_COMPLETION = 100;

    static final String FORCE_IMMEDIATE_DECISION_TIMER = "FORCE_IMMEDIATE_DECISION";

    private final PollForDecisionTaskResponse task;

    private long idCounter;

    private final Map<Long, String> activitySchedulingEventIdToActivityId = new HashMap<Long, String>();

    private final Map<Long, String> signalInitiatedEventIdToSignalId = new HashMap<Long, String>();

    private final Map<Long, String> lambdaSchedulingEventIdToLambdaId = new HashMap<Long, String>();

    /**
     * Use access-order to ensure that decisions are emitted in order of their
     * creation
     */
    private final Map<DecisionId, DecisionStateMachine> decisions = new LinkedHashMap<DecisionId, DecisionStateMachine>(100,
            0.75f, true);

    private Throwable workflowFailureCause;

    private byte[] workflowContextData;

    private byte[] workfowContextFromLastDecisionCompletion;

    DecisionsHelper(PollForDecisionTaskResponse task) {
        this.task = task;
    }

    void scheduleActivityTask(ScheduleActivityTaskDecisionAttributes schedule) {
        DecisionId decisionId = new DecisionId(DecisionTarget.ACTIVITY, schedule.getActivityId());
        addDecision(decisionId, new ActivityDecisionStateMachine(decisionId, schedule));
    }

    /**
     * @return true if cancellation already happened as schedule event was found
     * in the new decisions list
     */
    boolean requestCancelActivityTask(String activityId, Runnable immediateCancellationCallback) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.ACTIVITY, activityId));
        decision.cancel(immediateCancellationCallback);
        return decision.isDone();
    }

    boolean handleActivityTaskClosed(String activityId) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.ACTIVITY, activityId));
        decision.handleCompletionEvent();
        return decision.isDone();
    }

    boolean handleActivityTaskScheduled(HistoryEvent event) {
        ActivityTaskScheduledEventAttributes attributes = event.getActivityTaskScheduledEventAttributes();
        String activityId = attributes.getActivityId();
        activitySchedulingEventIdToActivityId.put(event.getEventId(), activityId);
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.ACTIVITY, activityId));
        decision.handleInitiatedEvent(event);
        return decision.isDone();
    }

    boolean handleActivityTaskCancelRequested(HistoryEvent event) {
        ActivityTaskCancelRequestedEventAttributes attributes = event.getActivityTaskCancelRequestedEventAttributes();
        String activityId = attributes.getActivityId();
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.ACTIVITY, activityId));
        decision.handleCancellationInitiatedEvent();
        return decision.isDone();
    }

    public boolean handleActivityTaskCanceled(HistoryEvent event) {
        ActivityTaskCanceledEventAttributes attributes = event.getActivityTaskCanceledEventAttributes();
        String activityId = getActivityId(attributes);
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.ACTIVITY, activityId));
        decision.handleCancellationEvent();
        return decision.isDone();
    }

    boolean handleRequestCancelActivityTaskFailed(HistoryEvent event) {
        RequestCancelActivityTaskFailedEventAttributes attributes = event.getRequestCancelActivityTaskFailedEventAttributes();
        String activityId = attributes.getActivityId();
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.ACTIVITY, activityId));
        decision.handleCancellationFailureEvent(event);
        return decision.isDone();
    }

    void startChildWorkflowExecution(StartChildWorkflowExecutionDecisionAttributes schedule) {
        DecisionId decisionId = new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, schedule.getWorkflowId());
        addDecision(decisionId, new ChildWorkflowDecisionStateMachine(decisionId, schedule));
    }

    void handleStartChildWorkflowExecutionInitiated(HistoryEvent event) {
        StartChildWorkflowExecutionInitiatedEventAttributes attributes = event.getStartChildWorkflowExecutionInitiatedEventAttributes();
        String workflowId = attributes.getWorkflowId();
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, workflowId));
        decision.handleInitiatedEvent(event);
    }

    public boolean handleStartChildWorkflowExecutionFailed(HistoryEvent event) {
        StartChildWorkflowExecutionFailedEventAttributes attributes = event.getStartChildWorkflowExecutionFailedEventAttributes();
        String workflowId = attributes.getWorkflowId();
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, workflowId));
        decision.handleInitiationFailedEvent(event);
        return decision.isDone();
    }

    /**
     * @return true if cancellation already happened as schedule event was found
     * in the new decisions list
     */
    boolean requestCancelExternalWorkflowExecution(boolean childWorkflow,
                                                   RequestCancelExternalWorkflowExecutionDecisionAttributes request, Runnable immediateCancellationCallback) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, request.getWorkflowId()));
        decision.cancel(immediateCancellationCallback);
        return decision.isDone();
    }

    void handleRequestCancelExternalWorkflowExecutionInitiated(HistoryEvent event) {
        RequestCancelExternalWorkflowExecutionInitiatedEventAttributes attributes = event.getRequestCancelExternalWorkflowExecutionInitiatedEventAttributes();
        String workflowId = attributes.getWorkflowExecution().getWorkflowId();
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, workflowId));
        decision.handleCancellationInitiatedEvent();
    }

    void handleRequestCancelExternalWorkflowExecutionFailed(HistoryEvent event) {
        RequestCancelExternalWorkflowExecutionFailedEventAttributes attributes = event.getRequestCancelExternalWorkflowExecutionFailedEventAttributes();
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, attributes.getWorkflowExecution().getWorkflowId()));
        decision.handleCancellationFailureEvent(event);
    }

    public boolean handleSignalExternalWorkflowExecutionFailed(String signalId) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.SIGNAL, signalId));
        decision.handleCompletionEvent();
        return decision.isDone();
    }

    public boolean handleExternalWorkflowExecutionSignaled(String signalId) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.SIGNAL, signalId));
        decision.handleCompletionEvent();
        return decision.isDone();
    }

    void startTimer(StartTimerDecisionAttributes request, Object createTimerUserContext) {
        String timerId = request.getTimerId();
        DecisionId decisionId = new DecisionId(DecisionTarget.TIMER, timerId);
        addDecision(decisionId, new TimerDecisionStateMachine(decisionId, request));
    }

    boolean cancelTimer(String timerId, Runnable immediateCancellationCallback) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.TIMER, timerId));
        decision.cancel(immediateCancellationCallback);
        return decision.isDone();
    }

    public void handleChildWorkflowExecutionStarted(HistoryEvent event) {
        ChildWorkflowExecutionStartedEventAttributes attributes = event.getChildWorkflowExecutionStartedEventAttributes();
        String workflowId = attributes.getWorkflowExecution().getWorkflowId();
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, workflowId));
        decision.handleStartedEvent(event);
    }

    boolean handleChildWorkflowExecutionClosed(String workflowId) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, workflowId));
        decision.handleCompletionEvent();
        return decision.isDone();
    }

    public void handleChildWorkflowExecutionCancelRequested(HistoryEvent event) {
    }

    public boolean handleChildWorkflowExecutionCanceled(String workflowId) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, workflowId));
        decision.handleCancellationEvent();
        return decision.isDone();
    }

    boolean handleTimerClosed(String timerId) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.TIMER, timerId));
        decision.handleCompletionEvent();
        return decision.isDone();
    }

    boolean handleTimerStarted(HistoryEvent event) {
        TimerStartedEventAttributes attributes = event.getTimerStartedEventAttributes();
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.TIMER, attributes.getTimerId()));
        decision.handleInitiatedEvent(event);
        return decision.isDone();
    }

    boolean handleTimerCanceled(HistoryEvent event) {
        TimerCanceledEventAttributes attributes = event.getTimerCanceledEventAttributes();
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.TIMER, attributes.getTimerId()));
        decision.handleCancellationEvent();
        return decision.isDone();
    }

    boolean handleCancelTimerFailed(HistoryEvent event) {
        CancelTimerFailedEventAttributes attributes = event.getCancelTimerFailedEventAttributes();
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.TIMER, attributes.getTimerId()));
        decision.handleCancellationFailureEvent(event);
        return decision.isDone();
    }

    void completeWorkflowExecution(byte[] output) {
        Decision decision = new Decision();
        CompleteWorkflowExecutionDecisionAttributes complete = new CompleteWorkflowExecutionDecisionAttributes();
        complete.setResult(output);
        decision.setCompleteWorkflowExecutionDecisionAttributes(complete);
        decision.setDecisionType(DecisionType.CompleteWorkflowExecution);
        DecisionId decisionId = new DecisionId(DecisionTarget.SELF, null);
        addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
    }

    void continueAsNewWorkflowExecution(ContinueAsNewWorkflowExecutionParameters continueParameters) {
        WorkflowExecutionStartedEventAttributes startedEvent = task.getHistory().getEvents().get(0).getWorkflowExecutionStartedEventAttributes();
        ContinueAsNewWorkflowExecutionDecisionAttributes attributes = new ContinueAsNewWorkflowExecutionDecisionAttributes();
        attributes.setWorkflowType(task.getWorkflowType());
        attributes.setInput(continueParameters.getInput());
        int executionStartToClose = continueParameters.getExecutionStartToCloseTimeoutSeconds();
        if (executionStartToClose == 0) {
            executionStartToClose = startedEvent.getExecutionStartToCloseTimeoutSeconds();
        }
        attributes.setExecutionStartToCloseTimeoutSeconds(executionStartToClose);
        int taskStartToClose = continueParameters.getTaskStartToCloseTimeoutSeconds();
        if (taskStartToClose == 0) {
            taskStartToClose = startedEvent.getTaskStartToCloseTimeoutSeconds();
        }
        attributes.setTaskStartToCloseTimeoutSeconds(taskStartToClose);
        String taskList = continueParameters.getTaskList();
        if (taskList == null || taskList.isEmpty()) {
            taskList = startedEvent.getTaskList().getName();
        }
        TaskList tl = new TaskList();
        tl.setName(taskList);
        attributes.setTaskList(tl);
        Decision decision = new Decision();
        decision.setDecisionType(DecisionType.ContinueAsNewWorkflowExecution);
        decision.setContinueAsNewWorkflowExecutionDecisionAttributes(attributes);

        DecisionId decisionId = new DecisionId(DecisionTarget.SELF, null);
        addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
    }

    void failWorkflowExecution(Throwable e) {
        Decision decision = new Decision();
        FailWorkflowExecutionDecisionAttributes fail = createFailWorkflowInstanceAttributes(e);
        decision.setFailWorkflowExecutionDecisionAttributes(fail);
        decision.setDecisionType(DecisionType.FailWorkflowExecution);
        DecisionId decisionId = new DecisionId(DecisionTarget.SELF, null);
        addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
        workflowFailureCause = e;
    }

    void failWorkflowDueToUnexpectedError(Throwable e) {
        // To make sure that failure goes through do not make any other decisions
        decisions.clear();
        this.failWorkflowExecution(e);
    }

    void handleCompleteWorkflowExecutionFailed(HistoryEvent event) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.SELF, null));
        decision.handleInitiationFailedEvent(event);
    }

    void handleFailWorkflowExecutionFailed(HistoryEvent event) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.SELF, null));
        decision.handleInitiationFailedEvent(event);
    }

    void handleCancelWorkflowExecutionFailed(HistoryEvent event) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.SELF, null));
        decision.handleInitiationFailedEvent(event);
    }

    void handleContinueAsNewWorkflowExecutionFailed(HistoryEvent event) {
        DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.SELF, null));
        decision.handleInitiationFailedEvent(event);
    }

    /**
     * @return <code>false</code> means that cancel failed, <code>true</code>
     * that CancelWorkflowExecution was created.
     */
    void cancelWorkflowExecution() {
        Decision decision = new Decision();
        CancelWorkflowExecutionDecisionAttributes cancel = new CancelWorkflowExecutionDecisionAttributes();
        cancel.setDetails((byte[]) null);
        decision.setCancelWorkflowExecutionDecisionAttributes(cancel);
        decision.setDecisionType(DecisionType.CancelWorkflowExecution);
        DecisionId decisionId = new DecisionId(DecisionTarget.SELF, null);
        addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
    }

    List<Decision> getDecisions() {
        List<Decision> result = new ArrayList<Decision>(MAXIMUM_DECISIONS_PER_COMPLETION + 1);
        for (DecisionStateMachine decisionStateMachine : decisions.values()) {
            Decision decision = decisionStateMachine.getDecision();
            if (decision != null) {
                result.add(decision);
            }
        }
        // Include FORCE_IMMEDIATE_DECISION timer only if there are more then 100 events
        int size = result.size();
        if (size > MAXIMUM_DECISIONS_PER_COMPLETION && !isCompletionEvent(result.get(MAXIMUM_DECISIONS_PER_COMPLETION - 2))) {
            result = result.subList(0, MAXIMUM_DECISIONS_PER_COMPLETION - 1);
            StartTimerDecisionAttributes attributes = new StartTimerDecisionAttributes();
            attributes.setStartToFireTimeoutSeconds(0);
            attributes.setTimerId(FORCE_IMMEDIATE_DECISION_TIMER);
            Decision d = new Decision();
            d.setStartTimerDecisionAttributes(attributes);
            d.setDecisionType(DecisionType.StartTimer);
            result.add(d);
        }

        return result;
    }

    private boolean isCompletionEvent(Decision decision) {
        DecisionType type = decision.getDecisionType();
        switch (type) {
            case CancelWorkflowExecution:
            case CompleteWorkflowExecution:
            case FailWorkflowExecution:
            case ContinueAsNewWorkflowExecution:
                return true;
            default:
                return false;
        }
    }

    public void handleDecisionTaskStartedEvent() {
        int count = 0;
        Iterator<DecisionStateMachine> iterator = decisions.values().iterator();
        DecisionStateMachine next = null;

        DecisionStateMachine decisionStateMachine = getNextDecision(iterator);
        while (decisionStateMachine != null) {
            next = getNextDecision(iterator);
            if (++count == MAXIMUM_DECISIONS_PER_COMPLETION && next != null && !isCompletionEvent(next.getDecision())) {
                break;
            }
            decisionStateMachine.handleDecisionTaskStartedEvent();
            decisionStateMachine = next;
        }
        if (next != null && count < MAXIMUM_DECISIONS_PER_COMPLETION) {
            next.handleDecisionTaskStartedEvent();
        }
    }

    private DecisionStateMachine getNextDecision(Iterator<DecisionStateMachine> iterator) {
        DecisionStateMachine result = null;
        while (result == null && iterator.hasNext()) {
            result = iterator.next();
            if (result.getDecision() == null) {
                result = null;
            }
        }
        return result;
    }

    public String toString() {
        return WorkflowExecutionUtils.prettyPrintDecisions(getDecisions());
    }

    boolean isWorkflowFailed() {
        return workflowFailureCause != null;
    }

    public Throwable getWorkflowFailureCause() {
        return workflowFailureCause;
    }

    byte[] getWorkflowContextData() {
        return workflowContextData;
    }

    void setWorkflowContextData(byte[] workflowState) {
        this.workflowContextData = workflowState;
    }

    /**
     * @return new workflow state or null if it didn't change since the last
     * decision completion
     */
    byte[] getWorkflowContextDataToReturn() {
        if (workfowContextFromLastDecisionCompletion == null
                || !workfowContextFromLastDecisionCompletion.equals(workflowContextData)) {
            return workflowContextData;
        }
        return null;
    }

    void handleDecisionCompletion(DecisionTaskCompletedEventAttributes decisionTaskCompletedEventAttributes) {
        workfowContextFromLastDecisionCompletion = decisionTaskCompletedEventAttributes.getExecutionContext();
    }

    PollForDecisionTaskResponse getTask() {
        return task;
    }

    String getActivityId(ActivityTaskCanceledEventAttributes attributes) {
        Long sourceId = attributes.getScheduledEventId();
        return activitySchedulingEventIdToActivityId.get(sourceId);
    }

    String getActivityId(ActivityTaskCompletedEventAttributes attributes) {
        Long sourceId = attributes.getScheduledEventId();
        return activitySchedulingEventIdToActivityId.get(sourceId);
    }

    String getActivityId(ActivityTaskFailedEventAttributes attributes) {
        Long sourceId = attributes.getScheduledEventId();
        return activitySchedulingEventIdToActivityId.get(sourceId);
    }

    String getActivityId(ActivityTaskTimedOutEventAttributes attributes) {
        Long sourceId = attributes.getScheduledEventId();
        return activitySchedulingEventIdToActivityId.get(sourceId);
    }

    String getSignalIdFromExternalWorkflowExecutionSignaled(long initiatedEventId) {
        return signalInitiatedEventIdToSignalId.get(initiatedEventId);
    }

    private FailWorkflowExecutionDecisionAttributes createFailWorkflowInstanceAttributes(Throwable failure) {
        String reason;
        byte[] details;
        if (failure instanceof WorkflowException) {
            WorkflowException f = (WorkflowException) failure;
            reason = f.getReason();
            details = f.getDetails();
        } else {
            reason = failure.toString();
            StringWriter sw = new StringWriter();
            failure.printStackTrace(new PrintWriter(sw));
            details = sw.toString().getBytes(TaskPoller.UTF8_CHARSET);
        }
        FailWorkflowExecutionDecisionAttributes result = new FailWorkflowExecutionDecisionAttributes();
        result.setReason(WorkflowExecutionUtils.truncateReason(reason));
        result.setDetails(details);
        return result;
    }

    void addDecision(DecisionId decisionId, DecisionStateMachine decision) {
        decisions.put(decisionId, decision);
    }

    private DecisionStateMachine getDecision(DecisionId decisionId) {
        DecisionStateMachine result = decisions.get(decisionId);
        if (result == null) {
            throw new IllegalArgumentException("Unknown " + decisionId + ". The possible causes are "
                    + "nondeterministic workflow definition code or incompatible change in the workflow definition.");
        }
        return result;
    }

    public String getNextId() {
        return String.valueOf(++idCounter);
    }

}
