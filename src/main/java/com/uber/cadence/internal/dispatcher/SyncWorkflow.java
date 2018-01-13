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

import com.uber.cadence.AsyncDecisionContext;
import com.uber.cadence.DataConverter;
import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.WorkflowException;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.worker.AsyncWorkflow;

import java.util.concurrent.CancellationException;
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
    private final DataConverter converter;
    private final ExecutorService threadPool;
    private WorkflowRunnable runnable;
    private DeterministicRunner runner;

    public SyncWorkflow(Function<WorkflowType, SyncWorkflowDefinition> factory, DataConverter converter, ExecutorService threadPool) {
        this.factory = factory;
        this.converter = converter;
        this.threadPool = threadPool;
    }

    @Override
    public void start(HistoryEvent event, AsyncDecisionContext context) {
        WorkflowType workflowType = event.getWorkflowExecutionStartedEventAttributes().getWorkflowType();
        SyncWorkflowDefinition workflow = factory.apply(workflowType);
        SyncDecisionContext syncContext = new SyncDecisionContext(context, converter);
        if (event.getEventType() != EventType.WorkflowExecutionStarted) {
            throw new IllegalArgumentException("first event is not WorkflowExecutionStarted, but "
                    + event.getEventType());
        }

        runnable = new WorkflowRunnable(syncContext, workflow, event.getWorkflowExecutionStartedEventAttributes());
        runner = DeterministicRunner.newRunner(threadPool, syncContext, context.getWorkflowClock()::currentTimeMillis, runnable);
    }

    @Override
    public void processSignal(String signalName, byte[] input) {
        runnable.processSignal(signalName, input);
    }

    @Override
    public boolean eventLoop() throws Throwable {
        if (runner == null) {
            return false;
        }
        runner.runUntilAllBlocked();
        return runner.isDone();
    }

    @Override
    public byte[] getOutput() {
        return runnable.getOutput();
    }

    @Override
    public void cancel(CancellationException e) {
        runnable.cancel(e);
    }

    @Override
    public Throwable getFailure() {
        return runnable.getFailure();
    }

    @Override
    public boolean isCancelRequested() {
        return runnable.isCancelRequested();
    }

    @Override
    public String getAsynchronousThreadDump() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public byte[] getWorkflowState() throws WorkflowException {
        throw new UnsupportedOperationException("not supported by Cadence, use query instead");
    }

    @Override
    public void close() {
        runnable.close();
    }

    @Override
    public long getNextWakeUpTime() {
        return runner.getNextWakeUpTime();
    }
}
