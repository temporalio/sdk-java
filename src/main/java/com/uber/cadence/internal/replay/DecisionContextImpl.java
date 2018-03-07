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

import com.uber.cadence.ActivityTaskStartedEventAttributes;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.TimerFiredEventAttributes;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import com.uber.cadence.WorkflowType;

import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class DecisionContextImpl implements DecisionContext, HistoryEventHandler {

    private final ActivityDecisionContext activityClient;

    private final WorkflowDecisionContext workflowClient;

    private final ClockDecisionContext workflowClock;

    private final WorkflowContext workflowContext;

    DecisionContextImpl(DecisionsHelper decisionsHelper, String domain, PollForDecisionTaskResponse decisionTask,
                        WorkflowExecutionStartedEventAttributes startedAttributes) {
        this.activityClient = new ActivityDecisionContext(decisionsHelper);
        this.workflowContext = new WorkflowContext(domain, decisionTask, startedAttributes);
        this.workflowClient = new WorkflowDecisionContext(decisionsHelper, workflowContext);
        this.workflowClock = new ClockDecisionContext(decisionsHelper);
    }

    @Override
    public WorkflowExecution getWorkflowExecution() {
        return workflowContext.getWorkflowExecution();
    }

    @Override
    public WorkflowType getWorkflowType() {
        return workflowContext.getWorkflowType();
    }

    @Override
    public boolean isCancelRequested() {
        return workflowContext.isCancelRequested();
    }

    public void setCancelRequested(boolean flag) {
        workflowContext.setCancelRequested(flag);
    }

    @Override
    public ContinueAsNewWorkflowExecutionParameters getContinueAsNewOnCompletion() {
        return workflowContext.getContinueAsNewOnCompletion();
    }

    @Override
    public void setContinueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters continueParameters) {
        workflowContext.setContinueAsNewOnCompletion(continueParameters);
    }

    @Override
    public int getExecutionStartToCloseTimeoutSeconds() {
        return workflowContext.getExecutionStartToCloseTimeoutSeconds();
    }

    @Override
    public Duration getDecisionTaskTimeout() {
        return Duration.ofSeconds(workflowContext.getDecisionTaskTimeoutSeconds());
    }

    @Override
    public String getTaskList() {
        return workflowContext.getTaskList();
    }

    @Override
    public String getDomain() {
        return workflowContext.getDomain();
    }

    @Override
    public String getWorkflowId() {
        return workflowContext.getWorkflowExecution().getWorkflowId();
    }

    @Override
    public String getRunId() {
        return workflowContext.getWorkflowExecution().getRunId();
    }

    @Override
    public Duration getExecutionStartToCloseTimeout() {
        return Duration.ofSeconds(workflowContext.getExecutionStartToCloseTimeoutSeconds());
    }

    @Override
    public Consumer<Exception> scheduleActivityTask(ExecuteActivityParameters parameters,
                                                    BiConsumer<byte[], Exception> callback) {
        return activityClient.scheduleActivityTask(parameters, callback);
    }

    @Override
    public Consumer<Exception> startChildWorkflow(StartChildWorkflowExecutionParameters parameters,
                                                  Consumer<WorkflowExecution> executionCallback,
                                                  BiConsumer<byte[], Exception> callback) {
        return workflowClient.startChildWorkflow(parameters, executionCallback, callback);
    }


    public Consumer<Exception> signalWorkflowExecution(SignalExternalWorkflowParameters signalParameters,
                                                       BiConsumer<Void, Exception> callback) {
        return workflowClient.signalWorkflowExecution(signalParameters, callback);
    }

    @Override
    public void requestCancelWorkflowExecution(WorkflowExecution execution) {
        workflowClient.requestCancelWorkflowExecution(execution);
    }

    @Override
    public void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters parameters) {
        workflowClient.continueAsNewOnCompletion(parameters);
    }

    @Override
    public String generateUniqueId() {
        return workflowClient.generateUniqueId();
    }

    public void setReplayCurrentTimeMilliseconds(long replayCurrentTimeMilliseconds) {
        workflowClock.setReplayCurrentTimeMilliseconds(replayCurrentTimeMilliseconds);
    }

    @Override
    public boolean isReplaying() {
        return workflowClock.isReplaying();
    }

    @Override
    public Consumer<Exception> createTimer(long delaySeconds, Consumer<Exception> callback) {
        return workflowClock.createTimer(delaySeconds, callback);
    }

    @Override
    public void cancelAllTimers() {
        workflowClock.cancelAllTimers();
    }

    @Override
    public long currentTimeMillis() {
        return workflowClock.currentTimeMillis();
    }

    public void setReplaying(boolean replaying) {
        workflowClock.setReplaying(replaying);
    }

    @Override
    public void handleActivityTaskStarted(ActivityTaskStartedEventAttributes attributes) {
        activityClient.handleActivityTaskStarted(attributes);
    }

    @Override
    public void handleActivityTaskCanceled(HistoryEvent event) {
        activityClient.handleActivityTaskCanceled(event);
    }

    @Override
    public void handleActivityTaskCompleted(HistoryEvent event) {
        activityClient.handleActivityTaskCompleted(event);
    }

    @Override
    public void handleActivityTaskFailed(HistoryEvent event) {
        activityClient.handleActivityTaskFailed(event);
    }

    @Override
    public void handleActivityTaskTimedOut(HistoryEvent event) {
        activityClient.handleActivityTaskTimedOut(event);
    }

    @Override
    public void handleChildWorkflowExecutionCancelRequested(HistoryEvent event) {
        workflowClient.handleChildWorkflowExecutionCancelRequested(event);
    }

    @Override
    public void handleChildWorkflowExecutionCanceled(HistoryEvent event) {
        workflowClient.handleChildWorkflowExecutionCanceled(event);
    }

    @Override
    public void handleChildWorkflowExecutionStarted(HistoryEvent event) {
        workflowClient.handleChildWorkflowExecutionStarted(event);
    }

    @Override
    public void handleChildWorkflowExecutionTimedOut(HistoryEvent event) {
        workflowClient.handleChildWorkflowExecutionTimedOut(event);
    }

    @Override
    public void handleChildWorkflowExecutionTerminated(HistoryEvent event) {
        workflowClient.handleChildWorkflowExecutionTerminated(event);
    }

    @Override
    public void handleStartChildWorkflowExecutionFailed(HistoryEvent event) {
        workflowClient.handleStartChildWorkflowExecutionFailed(event);
    }

    @Override
    public void handleChildWorkflowExecutionFailed(HistoryEvent event) {
        workflowClient.handleChildWorkflowExecutionFailed(event);
    }

    @Override
    public void handleChildWorkflowExecutionCompleted(HistoryEvent event) {
        workflowClient.handleChildWorkflowExecutionCompleted(event);
    }

    @Override
    public void handleTimerFired(TimerFiredEventAttributes attributes) {
        workflowClock.handleTimerFired(attributes);
    }

    @Override
    public void handleTimerCanceled(HistoryEvent event) {
        workflowClock.handleTimerCanceled(event);
    }

    public void handleSignalExternalWorkflowExecutionFailed(HistoryEvent event) {
        workflowClient.handleSignalExternalWorkflowExecutionFailed(event);
    }

    public void handleExternalWorkflowExecutionSignaled(HistoryEvent event) {
        workflowClient.handleExternalWorkflowExecutionSignaled(event);
    }
}
