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
package com.uber.cadence.internal.dispatcher;

import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.internal.worker.DecisionContext;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.worker.AsyncWorkflow;
import com.uber.cadence.internal.worker.POJOWorkflowImplementationFactory;
import com.uber.cadence.internal.worker.WorkflowExecutionException;

import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * The best inheritance hierarchy :).
 * SyncWorkflow supports workflows that use blocking code.
 * <p>
 * TODO: rename AsyncWorkflow to something more reasonable.
 */
class SyncWorkflow implements AsyncWorkflow {

    private final Function<WorkflowType, SyncWorkflowDefinition> factory;
    private final DataConverter dataConverter;
    private final ExecutorService threadPool;
    private WorkflowRunnable workflowProc;
    private DeterministicRunner runner;

    public SyncWorkflow(Function<WorkflowType, SyncWorkflowDefinition> factory, DataConverter dataConverter, ExecutorService threadPool) {
        this.factory = factory;
        this.dataConverter = dataConverter;
        this.threadPool = threadPool;
    }

    @Override
    public void start(HistoryEvent event, DecisionContext context) {
        WorkflowType workflowType = event.getWorkflowExecutionStartedEventAttributes().getWorkflowType();
        SyncWorkflowDefinition workflow = factory.apply(workflowType);
        if (workflow == null) {
            throw new IllegalArgumentException("Unknown workflow type: " + workflowType);
        }
        SyncDecisionContext syncContext = new SyncDecisionContext(context, dataConverter);
        if (event.getEventType() != EventType.WorkflowExecutionStarted) {
            throw new IllegalArgumentException("first event is not WorkflowExecutionStarted, but "
                    + event.getEventType());
        }

        workflowProc = new WorkflowRunnable(syncContext, workflow, event.getWorkflowExecutionStartedEventAttributes());
        runner = DeterministicRunner.newRunner(threadPool, syncContext, context::currentTimeMillis, workflowProc);
        syncContext.setRunner(runner);
    }

    @Override
    public void handleSignal(String signalName, byte[] input, long eventId) {
        String threadName = "\"" + signalName + "\" signal handler";
        runner.executeInWorkflowThread(threadName, () -> workflowProc.processSignal(signalName, input, eventId));
    }

    @Override
    public boolean eventLoop() throws Throwable {
        if (runner == null) {
            return false;
        }
        workflowProc.fireTimers();
        runner.runUntilAllBlocked();
        return runner.isDone() || workflowProc.isDone(); // Do not wait for all other threads.
    }

    @Override
    public byte[] getOutput() {
        return workflowProc.getOutput();
    }

    @Override
    public void cancel(String reason) {
        runner.cancel(reason);
    }

    @Override
    public Throwable getFailure() {
        return workflowProc.getFailure();
    }

    @Override
    public boolean isCancelRequested() {
        return workflowProc.isCancelRequested();
    }

    @Override
    public void close() {
        if (runner != null) {
            runner.close();
        }
    }

    @Override
    public long getNextWakeUpTime() {
        return runner.getNextWakeUpTime();
    }

    @Override
    public byte[] query(WorkflowQuery query) {
        if (WorkflowClient.QUERY_TYPE_STACK_TRCE.equals(query.getQueryType())) {
            return dataConverter.toData(runner.stackTrace());
        }
        return workflowProc.query(query.getQueryType(), query.getQueryArgs());
    }

    @Override
    public WorkflowExecutionException mapUnexpectedException(Throwable failure) {
        return POJOWorkflowImplementationFactory.mapToWorkflowExecutionException(failure, dataConverter);
    }
}
