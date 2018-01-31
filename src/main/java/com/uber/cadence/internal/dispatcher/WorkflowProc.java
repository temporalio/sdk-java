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

import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import com.uber.cadence.workflow.Functions;

import java.util.concurrent.CancellationException;

class WorkflowProc implements Functions.Proc {
    private final SyncDecisionContext context;
    private final SyncWorkflowDefinition workflow;
    private final WorkflowExecutionStartedEventAttributes attributes;

    private Throwable failure;
    private boolean cancelRequested;
    private byte[] output;
    private boolean done;

    public WorkflowProc(SyncDecisionContext syncDecisionContext,
                        SyncWorkflowDefinition workflow,
                        WorkflowExecutionStartedEventAttributes attributes) {
        this.context = syncDecisionContext;
        this.workflow = workflow;
        this.attributes = attributes;
    }

    @Override
    public void apply() throws Exception {
        output = workflow.execute(attributes.getInput());
        done = true;
    }

    public void cancel(CancellationException e) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public Throwable getFailure() {
        return failure;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public boolean isDone() {
        return done;
    }

    public byte[] getOutput() {
        return output;
    }

    public void close() {
    }

    public void processSignal(String signalName, byte[] input) {
        workflow.processSignal(signalName, input);
    }

    public byte[] query(String type, byte[] args) throws Exception {
        return context.query(type, args);
    }
}
