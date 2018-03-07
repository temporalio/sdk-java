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

import com.uber.cadence.ChildPolicy;
import com.uber.cadence.ChildWorkflowExecutionCanceledEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionCompletedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionFailedCause;
import com.uber.cadence.ChildWorkflowExecutionFailedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionStartedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionTerminatedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionTimedOutEventAttributes;
import com.uber.cadence.ExternalWorkflowExecutionSignaledEventAttributes;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.RequestCancelExternalWorkflowExecutionDecisionAttributes;
import com.uber.cadence.SignalExternalWorkflowExecutionDecisionAttributes;
import com.uber.cadence.SignalExternalWorkflowExecutionFailedEventAttributes;
import com.uber.cadence.StartChildWorkflowExecutionDecisionAttributes;
import com.uber.cadence.StartChildWorkflowExecutionFailedEventAttributes;
import com.uber.cadence.TaskList;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.workflow.ChildWorkflowTerminatedException;
import com.uber.cadence.workflow.ChildWorkflowTimedOutException;
import com.uber.cadence.workflow.SignalExternalWorkflowException;
import com.uber.cadence.workflow.StartChildWorkflowFailedException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class WorkflowDecisionContext {

    private final class ChildWorkflowCancellationHandler implements Consumer<Exception> {

        private final String workflowId;

        private final BiConsumer<byte[], Exception> callback;

        private ChildWorkflowCancellationHandler(String workflowId, BiConsumer<byte[], Exception> callback) {
            this.workflowId = workflowId;
            this.callback = callback;
        }

        @Override
        public void accept(Exception cause) {
            RequestCancelExternalWorkflowExecutionDecisionAttributes cancelAttributes = new RequestCancelExternalWorkflowExecutionDecisionAttributes();
            cancelAttributes.setWorkflowId(workflowId);

            decisions.requestCancelExternalWorkflowExecution(true, cancelAttributes, () -> {
                OpenChildWorkflowRequestInfo scheduled = scheduledExternalWorkflows.remove(workflowId);
                if (scheduled == null) {
                    throw new IllegalArgumentException("Workflow \"" + workflowId + "\" wasn't scheduled");
                }
                callback.accept(null, new CancellationException("Cancelled by request"));
            });
        }
    }

    private final DecisionsHelper decisions;

    private final WorkflowContext workflowContext;

    private final Map<String, OpenChildWorkflowRequestInfo> scheduledExternalWorkflows = new HashMap<>();

    private final Map<String, OpenRequestInfo<Void, Void>> scheduledSignals = new HashMap<>();

    WorkflowDecisionContext(DecisionsHelper decisions, WorkflowContext workflowContext) {
        this.decisions = decisions;
        this.workflowContext = workflowContext;
    }

    Consumer<Exception> startChildWorkflow(StartChildWorkflowExecutionParameters parameters,
                                           Consumer<WorkflowExecution> executionCallback,
                                           BiConsumer<byte[], Exception> callback) {
        final StartChildWorkflowExecutionDecisionAttributes attributes = new StartChildWorkflowExecutionDecisionAttributes();
        attributes.setWorkflowType(parameters.getWorkflowType());
        String workflowId = parameters.getWorkflowId();
        if (workflowId == null) {
            workflowId = generateUniqueId();
        }
        attributes.setWorkflowId(workflowId);
        if (parameters.getDomain() == null) {
            // Could be removed as soon as server allows null for domain.
            attributes.setDomain(workflowContext.getDomain());
        } else {
            attributes.setDomain(parameters.getDomain());
        }
        attributes.setInput(parameters.getInput());
        if (parameters.getExecutionStartToCloseTimeoutSeconds() == 0) {
            // TODO: Substract time passed since the parent start
            attributes.setExecutionStartToCloseTimeoutSeconds(workflowContext.getExecutionStartToCloseTimeoutSeconds());
        } else {
            attributes.setExecutionStartToCloseTimeoutSeconds((int) parameters.getExecutionStartToCloseTimeoutSeconds());
        }
        if (parameters.getTaskStartToCloseTimeoutSeconds() == 0) {
            attributes.setTaskStartToCloseTimeoutSeconds(workflowContext.getDecisionTaskTimeoutSeconds());
        } else {
            attributes.setTaskStartToCloseTimeoutSeconds((int) parameters.getTaskStartToCloseTimeoutSeconds());
        }
        if (parameters.getChildPolicy() == null) {
            // TODO: Child policy from a parent as soon as it is available in the WorkflowExecutionStarted event
            // Or when server accepts null
//            attributes.setChildPolicy(workflowContext.getChildPolicy());
            attributes.setChildPolicy(ChildPolicy.TERMINATE);
        } else {
            attributes.setChildPolicy(parameters.getChildPolicy());
        }
        String taskList = parameters.getTaskList();
        TaskList tl = new TaskList();
        if (taskList != null && !taskList.isEmpty()) {
            tl.setName(taskList);
        } else {
            tl.setName(workflowContext.getTaskList());
        }
        attributes.setTaskList(tl);
        attributes.setWorkflowIdReusePolicy(parameters.getWorkflowIdReusePolicy());
        decisions.startChildWorkflowExecution(attributes);
        final OpenChildWorkflowRequestInfo context = new OpenChildWorkflowRequestInfo(executionCallback);
        context.setCompletionHandle(callback);
        scheduledExternalWorkflows.put(attributes.getWorkflowId(), context);
        return new ChildWorkflowCancellationHandler(attributes.getWorkflowId(), callback);
    }

    Consumer<Exception> signalWorkflowExecution(final SignalExternalWorkflowParameters parameters, BiConsumer<Void, Exception> callback) {
        final OpenRequestInfo<Void, Void> context = new OpenRequestInfo<>();
        final SignalExternalWorkflowExecutionDecisionAttributes attributes = new SignalExternalWorkflowExecutionDecisionAttributes();
        String signalId = decisions.getNextId();
        if (parameters.getDomain() == null) {
            attributes.setDomain(workflowContext.getDomain());
        } else {
            attributes.setDomain(parameters.getDomain());
        }
        attributes.setControl(signalId.getBytes(StandardCharsets.UTF_8));
        attributes.setSignalName(parameters.getSignalName());
        attributes.setInput(parameters.getInput());
        WorkflowExecution execution = new WorkflowExecution();
        execution.setRunId(parameters.getRunId());
        execution.setWorkflowId(parameters.getWorkflowId());
        attributes.setExecution(execution);
        decisions.signalExternalWorkflowExecution(attributes);
        context.setCompletionHandle(callback);
        final String finalSignalId = new String(attributes.getControl(), StandardCharsets.UTF_8);
        scheduledSignals.put(finalSignalId, context);
        return (e) -> {
            decisions.cancelSignalExternalWorkflowExecution(finalSignalId, null);
            OpenRequestInfo<Void, Void> scheduled = scheduledSignals.remove(finalSignalId);
            if (scheduled == null) {
                throw new IllegalArgumentException("Signal \"" + finalSignalId + "\" wasn't scheduled");
            }
            callback.accept(null, e);
        };
    }

    void requestCancelWorkflowExecution(WorkflowExecution execution) {
        RequestCancelExternalWorkflowExecutionDecisionAttributes attributes = new RequestCancelExternalWorkflowExecutionDecisionAttributes();
        String workflowId = execution.getWorkflowId();
        attributes.setWorkflowId(workflowId);
        attributes.setRunId(execution.getRunId());
        boolean childWorkflow = scheduledExternalWorkflows.containsKey(workflowId);
        // TODO: See if immediate cancellation needed
        decisions.requestCancelExternalWorkflowExecution(childWorkflow, attributes, null);
    }

    void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters continueParameters) {

        // TODO: add validation to check if continueAsNew is not set
        workflowContext.setContinueAsNewOnCompletion(continueParameters);
    }

    String generateUniqueId() {
        WorkflowExecution workflowExecution = workflowContext.getWorkflowExecution();
        String runId = workflowExecution.getRunId();
        return runId + ":" + decisions.getNextId();
    }

    void handleChildWorkflowExecutionCancelRequested(HistoryEvent event) {
        decisions.handleChildWorkflowExecutionCancelRequested(event);
    }

    void handleChildWorkflowExecutionCanceled(HistoryEvent event) {
        ChildWorkflowExecutionCanceledEventAttributes attributes = event.getChildWorkflowExecutionCanceledEventAttributes();
        WorkflowExecution execution = attributes.getWorkflowExecution();
        String workflowId = execution.getWorkflowId();
        if (decisions.handleChildWorkflowExecutionCanceled(workflowId)) {
            OpenChildWorkflowRequestInfo scheduled = scheduledExternalWorkflows.remove(workflowId);
            if (scheduled != null) {
                CancellationException e = new CancellationException();
                BiConsumer<byte[], Exception> completionCallback = scheduled.getCompletionCallback();
                completionCallback.accept(null, e);
            }
        }
    }

    void handleChildWorkflowExecutionStarted(HistoryEvent event) {
        ChildWorkflowExecutionStartedEventAttributes attributes = event.getChildWorkflowExecutionStartedEventAttributes();
        WorkflowExecution execution = attributes.getWorkflowExecution();
        String workflowId = execution.getWorkflowId();
        decisions.handleChildWorkflowExecutionStarted(event);
        OpenChildWorkflowRequestInfo scheduled = scheduledExternalWorkflows.get(workflowId);
        if (scheduled != null) {
            scheduled.getExecutionCallback().accept(attributes.getWorkflowExecution());
        }
    }

    void handleChildWorkflowExecutionTimedOut(HistoryEvent event) {
        ChildWorkflowExecutionTimedOutEventAttributes attributes = event.getChildWorkflowExecutionTimedOutEventAttributes();
        WorkflowExecution execution = attributes.getWorkflowExecution();
        String workflowId = execution.getWorkflowId();
        if (decisions.handleChildWorkflowExecutionClosed(workflowId)) {
            OpenChildWorkflowRequestInfo scheduled = scheduledExternalWorkflows.remove(workflowId);
            if (scheduled != null) {
                RuntimeException failure = new ChildWorkflowTimedOutException(event.getEventId(), execution,
                        attributes.getWorkflowType());
                BiConsumer<byte[], Exception> completionCallback = scheduled.getCompletionCallback();
                completionCallback.accept(null, failure);
            }
        }
    }

    void handleChildWorkflowExecutionTerminated(HistoryEvent event) {
        ChildWorkflowExecutionTerminatedEventAttributes attributes = event.getChildWorkflowExecutionTerminatedEventAttributes();
        WorkflowExecution execution = attributes.getWorkflowExecution();
        String workflowId = execution.getWorkflowId();
        if (decisions.handleChildWorkflowExecutionClosed(workflowId)) {
            OpenChildWorkflowRequestInfo scheduled = scheduledExternalWorkflows.remove(workflowId);
            if (scheduled != null) {
                RuntimeException failure = new ChildWorkflowTerminatedException(
                        event.getEventId(), execution, attributes.getWorkflowType());
                BiConsumer<byte[], Exception> completionCallback = scheduled.getCompletionCallback();
                completionCallback.accept(null, failure);
            }
        }
    }

    void handleStartChildWorkflowExecutionFailed(HistoryEvent event) {
        StartChildWorkflowExecutionFailedEventAttributes attributes = event.getStartChildWorkflowExecutionFailedEventAttributes();
        String workflowId = attributes.getWorkflowId();
        if (decisions.handleStartChildWorkflowExecutionFailed(event)) {
            OpenChildWorkflowRequestInfo scheduled = scheduledExternalWorkflows.remove(workflowId);
            if (scheduled != null) {
                WorkflowExecution workflowExecution = new WorkflowExecution();
                workflowExecution.setWorkflowId(workflowId);
                WorkflowType workflowType = attributes.getWorkflowType();
                ChildWorkflowExecutionFailedCause cause = attributes.getCause();
                RuntimeException failure = new StartChildWorkflowFailedException(
                        event.getEventId(), workflowExecution, workflowType, cause);
                BiConsumer<byte[], Exception> completionCallback = scheduled.getCompletionCallback();
                completionCallback.accept(null, failure);
            }
        }
    }

    void handleChildWorkflowExecutionFailed(HistoryEvent event) {
        ChildWorkflowExecutionFailedEventAttributes attributes = event.getChildWorkflowExecutionFailedEventAttributes();
        WorkflowExecution execution = attributes.getWorkflowExecution();
        String workflowId = execution.getWorkflowId();
        if (decisions.handleChildWorkflowExecutionClosed(workflowId)) {
            OpenChildWorkflowRequestInfo scheduled = scheduledExternalWorkflows.remove(workflowId);
            if (scheduled != null) {
                String reason = attributes.getReason();
                byte[] details = attributes.getDetails();
                RuntimeException failure = new ChildWorkflowTaskFailedException(
                        event.getEventId(), execution, attributes.getWorkflowType(), reason, details);
                BiConsumer<byte[], Exception> completionCallback = scheduled.getCompletionCallback();
                completionCallback.accept(null, failure);
            }
        }
    }

    void handleChildWorkflowExecutionCompleted(HistoryEvent event) {
        ChildWorkflowExecutionCompletedEventAttributes attributes = event.getChildWorkflowExecutionCompletedEventAttributes();
        WorkflowExecution execution = attributes.getWorkflowExecution();
        String workflowId = execution.getWorkflowId();
        if (decisions.handleChildWorkflowExecutionClosed(workflowId)) {
            OpenChildWorkflowRequestInfo scheduled = scheduledExternalWorkflows.remove(workflowId);
            if (scheduled != null) {
                BiConsumer<byte[], Exception> completionCallback = scheduled.getCompletionCallback();
                byte[] result = attributes.getResult();
                completionCallback.accept(result, null);
            }
        }
    }

    void handleSignalExternalWorkflowExecutionFailed(HistoryEvent event) {
        SignalExternalWorkflowExecutionFailedEventAttributes attributes = event.getSignalExternalWorkflowExecutionFailedEventAttributes();
        String signalId = new String(attributes.getControl(), StandardCharsets.UTF_8);
        if (decisions.handleSignalExternalWorkflowExecutionFailed(signalId)) {
            OpenRequestInfo<Void, Void> signalContextAndResult = scheduledSignals.remove(signalId);
            if (signalContextAndResult != null) {
                WorkflowExecution signaledExecution = new WorkflowExecution();
                signaledExecution.setWorkflowId(attributes.getWorkflowExecution().getWorkflowId());
                signaledExecution.setRunId(attributes.getWorkflowExecution().getRunId());
                RuntimeException failure = new SignalExternalWorkflowException(event.getEventId(), signaledExecution,
                        attributes.getCause());
                signalContextAndResult.getCompletionCallback().accept(null, failure);
            }
        }
    }

    void handleExternalWorkflowExecutionSignaled(HistoryEvent event) {
        ExternalWorkflowExecutionSignaledEventAttributes attributes = event.getExternalWorkflowExecutionSignaledEventAttributes();
        String signalId = decisions.getSignalIdFromExternalWorkflowExecutionSignaled(attributes.getInitiatedEventId());
        if (decisions.handleExternalWorkflowExecutionSignaled(signalId)) {
            OpenRequestInfo<Void, Void> signalContextAndResult = scheduledSignals.remove(signalId);
            if (signalContextAndResult != null) {
                signalContextAndResult.getCompletionCallback().accept(null, null);
            }
        }
    }
}
