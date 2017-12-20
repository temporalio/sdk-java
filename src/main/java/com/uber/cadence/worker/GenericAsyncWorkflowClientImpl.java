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

import com.uber.cadence.ChildWorkflowFailedException;
import com.uber.cadence.ChildWorkflowTerminatedException;
import com.uber.cadence.ChildWorkflowTimedOutException;
import com.uber.cadence.StartChildWorkflowFailedException;
import com.uber.cadence.WorkflowContext;
import com.uber.cadence.generic.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.generic.GenericAsyncWorkflowClient;
import com.uber.cadence.generic.StartChildWorkflowExecutionParameters;
import com.uber.cadence.ChildPolicy;
import com.uber.cadence.ChildWorkflowExecutionCanceledEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionCompletedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionFailedCause;
import com.uber.cadence.ChildWorkflowExecutionFailedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionStartedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionTerminatedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionTimedOutEventAttributes;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.RequestCancelExternalWorkflowExecutionDecisionAttributes;
import com.uber.cadence.StartChildWorkflowExecutionDecisionAttributes;
import com.uber.cadence.StartChildWorkflowExecutionFailedEventAttributes;
import com.uber.cadence.TaskList;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

class GenericAsyncWorkflowClientImpl implements GenericAsyncWorkflowClient {

    private final class ChildWorkflowCancellationHandler implements Consumer<Throwable> {

        private final String workflowId;

        private final BiConsumer<byte[], Throwable> callback;

        private ChildWorkflowCancellationHandler(String workflowId, BiConsumer<byte[], Throwable> callback) {
            this.workflowId = workflowId;
            this.callback = callback;
        }

        @Override
        public void accept(Throwable cause) {
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

    private final Map<String, OpenChildWorkflowRequestInfo> scheduledExternalWorkflows = new HashMap<String, OpenChildWorkflowRequestInfo>();

    private final Map<String, OpenRequestInfo<Void, Void>> scheduledSignals = new HashMap<String, OpenRequestInfo<Void, Void>>();

    GenericAsyncWorkflowClientImpl(DecisionsHelper decisions, WorkflowContext workflowContext) {
        this.decisions = decisions;
        this.workflowContext = workflowContext;
    }

    @Override
    public Consumer<Throwable> startChildWorkflow(StartChildWorkflowExecutionParameters parameters,
                                                  Consumer<String> runIdCallback,
                                                  BiConsumer<byte[], Throwable> callback) {
        final StartChildWorkflowExecutionDecisionAttributes attributes = new StartChildWorkflowExecutionDecisionAttributes();
        attributes.setWorkflowType(parameters.getWorkflowType());
        String workflowId = parameters.getWorkflowId();
        if (workflowId == null) {
            workflowId = generateUniqueId();
        }
        attributes.setWorkflowId(workflowId);
        attributes.setInput(parameters.getInput());
        attributes.setExecutionStartToCloseTimeoutSeconds(parameters.getExecutionStartToCloseTimeoutSeconds());
        attributes.setTaskStartToCloseTimeoutSeconds(parameters.getTaskStartToCloseTimeoutSeconds());
//        attributes.setTaskPriority(FlowHelpers.taskPriorityToString(parameters.getTaskPriority()));
//        List<String> tagList = parameters.getTagList();
//        if (tagList != null) {
//            attributes.setTagList(tagList);
//        }
        ChildPolicy childPolicy = parameters.getChildPolicy();
        if (childPolicy != null) {
            attributes.setChildPolicy(childPolicy);
        }
        String taskList = parameters.getTaskList();
        if (taskList != null && !taskList.isEmpty()) {
            TaskList tl = new TaskList();
            tl.setName(taskList);
            attributes.setTaskList(tl);
        }
        decisions.startChildWorkflowExecution(attributes);
        final OpenChildWorkflowRequestInfo context = new OpenChildWorkflowRequestInfo(runIdCallback);
        context.setCompletionHandle(callback);
        scheduledExternalWorkflows.put(attributes.getWorkflowId(), context);
        return new ChildWorkflowCancellationHandler(attributes.getWorkflowId(), callback);
    }

    @Override
    public Consumer<Throwable> startChildWorkflow(String workflow, byte[] input, BiConsumer<byte[], Throwable> callback) {
        return startChildWorkflow(workflow, input, (s) -> {}, callback);
    }

    @Override
    public Consumer<Throwable> startChildWorkflow(String workflow, byte[] input, Consumer<String> runIdCallback, BiConsumer<byte[], Throwable> callback) {
        StartChildWorkflowExecutionParameters parameters = new StartChildWorkflowExecutionParameters();
        WorkflowType workflowType = new WorkflowType();
        workflowType.setName(workflow);
        parameters.setWorkflowType(workflowType);
        parameters.setInput(input);
        return startChildWorkflow(parameters, runIdCallback, (result, failure) -> {
                callback.accept(result, failure);
        });
    }

//    @Override
//    public Promise<Void> signalWorkflowExecution(final SignalExternalWorkflowParameters parameters) {
//        final OpenRequestInfo<Void, Void> context = new OpenRequestInfo<Void, Void>();
//        final SignalExternalWorkflowExecutionDecisionAttributes attributes = new SignalExternalWorkflowExecutionDecisionAttributes();
//        String signalId = decisions.getNextId();
//        attributes.setControl(signalId);
//        attributes.setSignalName(parameters.getSignalName());
//        attributes.setInput(parameters.getInput());
//        attributes.setRunId(parameters.getRunId());
//        attributes.setWorkflowId(parameters.getWorkflowId());
//        String taskName = "signalId=" + signalId + ", workflowId=" + parameters.getWorkflowId() + ", workflowRunId="
//                + parameters.getRunId();
//        new ExternalTask() {
//
//            @Override
//            protected ExternalTaskCancellationHandler doExecute(final ExternalTaskCompletionHandle callback) throws Throwable {
//
//                decisions.signalExternalWorkflowExecution(attributes);
//                context.setCompletionHandle(callback);
//                final String finalSignalId = attributes.getControl();
//                scheduledSignals.put(finalSignalId, context);
//                return new ExternalTaskCancellationHandler() {
//
//                    @Override
//                    public void handleCancellation(Throwable cause) {
//                        decisions.cancelSignalExternalWorkflowExecution(finalSignalId, null);
//                        OpenRequestInfo<Void, Void> scheduled = scheduledSignals.remove(finalSignalId);
//                        if (scheduled == null) {
//                            throw new IllegalArgumentException("Signal \"" + finalSignalId + "\" wasn't scheduled");
//                        }
//                        callback.complete();
//                    }
//                };
//            }
//        }.setName(taskName);
//        context.setResultDescription("signalWorkflowExecution " + taskName);
//        return context.getResult();
//    }

    @Override
    public void requestCancelWorkflowExecution(WorkflowExecution execution) {
        RequestCancelExternalWorkflowExecutionDecisionAttributes attributes = new RequestCancelExternalWorkflowExecutionDecisionAttributes();
        String workflowId = execution.getWorkflowId();
        attributes.setWorkflowId(workflowId);
        attributes.setRunId(execution.getRunId());
        boolean childWorkflow = scheduledExternalWorkflows.containsKey(workflowId);
        // TODO: See if immediate cancellation needed
        decisions.requestCancelExternalWorkflowExecution(childWorkflow, attributes, null);
    }

    @Override
    public void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters continueParameters) {

        // TODO: add validation to check if continueAsNew is not set 
        workflowContext.setContinueAsNewOnCompletion(continueParameters);
    }

    @Override
    public String generateUniqueId() {
        WorkflowExecution workflowExecution = workflowContext.getWorkflowExecution();
        String runId = workflowExecution.getRunId();
        return runId + ":" + decisions.getNextId();
    }

    public void handleChildWorkflowExecutionCancelRequested(HistoryEvent event) {
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
                BiConsumer<byte[], Throwable> completionCallback = scheduled.getCompletionCallback();
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
            String runId = attributes.getWorkflowExecution().getRunId();
            scheduled.getRunIdCallback().accept(runId);
        }
    }

    void handleChildWorkflowExecutionTimedOut(HistoryEvent event) {
        ChildWorkflowExecutionTimedOutEventAttributes attributes = event.getChildWorkflowExecutionTimedOutEventAttributes();
        WorkflowExecution execution = attributes.getWorkflowExecution();
        String workflowId = execution.getWorkflowId();
        if (decisions.handleChildWorkflowExecutionClosed(workflowId)) {
            OpenChildWorkflowRequestInfo scheduled = scheduledExternalWorkflows.remove(workflowId);
            if (scheduled != null) {
                Exception failure = new ChildWorkflowTimedOutException(event.getEventId(), execution,
                        attributes.getWorkflowType());
                BiConsumer<byte[], Throwable> completionCallback = scheduled.getCompletionCallback();
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
                Exception failure = new ChildWorkflowTerminatedException(event.getEventId(), execution,
                        attributes.getWorkflowType());
                BiConsumer<byte[], Throwable> completionCallback = scheduled.getCompletionCallback();
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
                Exception failure = new StartChildWorkflowFailedException(event.getEventId(), workflowExecution, workflowType,
                        cause);
                BiConsumer<byte[], Throwable> completionCallback = scheduled.getCompletionCallback();
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
                Exception failure = new ChildWorkflowFailedException(event.getEventId(), execution, attributes.getWorkflowType(),
                        reason, details);
                BiConsumer<byte[], Throwable> completionCallback = scheduled.getCompletionCallback();
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
                BiConsumer<byte[], Throwable> completionCallback = scheduled.getCompletionCallback();
                byte[] result = attributes.getResult();
                completionCallback.accept(result, null);
            }
        }
    }

    // TODO(Cadence): Impelement signal decision
//    void handleSignalExternalWorkflowExecutionFailed(HistoryEvent event) {
//        SignalExternalWorkflowExecutionFailedEventAttributes attributes = event.getSignalExternalWorkflowExecutionFailedEventAttributes();
//        String signalId = attributes.getControl();
//        if (decisions.handleSignalExternalWorkflowExecutionFailed(signalId)) {
//            OpenRequestInfo<Void, Void> signalContextAndResult = scheduledSignals.remove(signalId);
//            if (signalContextAndResult != null) {
//                WorkflowExecution signaledExecution = new WorkflowExecution();
//                signaledExecution.setWorkflowId(attributes.getWorkflowId());
//                signaledExecution.setRunId(attributes.getRunId());
//                Throwable failure = new SignalExternalWorkflowException(event.getEventId(), signaledExecution,
//                        attributes.getCause());
//                signalContextAndResult.getCompletionCallback().fail(failure);
//            }
//        }
//    }
//
//    void handleExternalWorkflowExecutionSignaled(HistoryEvent event) {
//        ExternalWorkflowExecutionSignaledEventAttributes attributes = event.getExternalWorkflowExecutionSignaledEventAttributes();
//        String signalId = decisions.getSignalIdFromExternalWorkflowExecutionSignaled(attributes.getInitiatedEventId());
//        if (decisions.handleExternalWorkflowExecutionSignaled(signalId)) {
//            OpenRequestInfo<Void, Void> signalContextAndResult = scheduledSignals.remove(signalId);
//            if (signalContextAndResult != null) {
//                signalContextAndResult.getResult().set(null);
//                signalContextAndResult.getCompletionCallback().complete();
//            }
//        }
//    }

}
