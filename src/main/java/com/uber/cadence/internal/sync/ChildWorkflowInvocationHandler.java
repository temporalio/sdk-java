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

package com.uber.cadence.internal.sync;

import com.google.common.base.Defaults;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.activity.MethodRetry;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.workflow.ChildWorkflowException;
import com.uber.cadence.workflow.ChildWorkflowOptions;
import com.uber.cadence.workflow.CompletablePromise;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.SignalExternalWorkflowException;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowMethod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Dynamic implementation of a strongly typed child workflow interface.
 */
class ChildWorkflowInvocationHandler implements InvocationHandler {

    private final ChildWorkflowOptions options;
    private final SyncDecisionContext decisionContext;
    private final DataConverter dataConverter;
    private CompletablePromise<WorkflowExecution> execution = Workflow.newPromise();
    private boolean startRequested;

    ChildWorkflowInvocationHandler(ChildWorkflowOptions options, SyncDecisionContext decisionContext) {
        this.options = options;
        this.decisionContext = decisionContext;
        dataConverter = decisionContext.getDataConverter();
    }

    public ChildWorkflowInvocationHandler(WorkflowExecution execution, SyncDecisionContext decisionContext) {
        this.options = null;
        this.decisionContext = decisionContext;
        dataConverter = decisionContext.getDataConverter();
        this.execution.complete(execution);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        // Implement WorkflowStub
        if (method.getName().equals(WorkflowStub.GET_EXECUTION_METHOD_NAME)) {
            return execution;
        }
        WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
        QueryMethod queryMethod = method.getAnnotation(QueryMethod.class);
        SignalMethod signalMethod = method.getAnnotation(SignalMethod.class);
        int count = (workflowMethod == null ? 0 : 1) + (queryMethod == null ? 0 : 1) + (signalMethod == null ? 0 : 1);
        if (count > 1) {
            throw new IllegalArgumentException(method + " must contain at most one annotation " +
                    "from @WorkflowMethod, @QueryMethod or @SignalMethod");
        }
        if (workflowMethod != null) {
            if (startRequested) {
                throw new IllegalStateException("Already started: " + execution);
            }
            startRequested = true;
            return executeChildWorkflow(method, workflowMethod, args);
        }
        if (queryMethod != null) {
            if (execution == null) {
                throw new IllegalStateException("Workflow not started yet");
            }
            return queryWorkflow(method, queryMethod, args);
        }
        if (signalMethod != null) {
            signalWorkflow(method, signalMethod, args);
            return null;
        }
        throw new IllegalArgumentException(method + " is not annotated with @WorkflowMethod or @QueryMethod");
    }

    private void signalWorkflow(Method method, SignalMethod signalMethod, Object[] args) {
        String signalName = signalMethod.name();
        if (signalName.isEmpty()) {
            signalName = InternalUtils.getSimpleName(method);
        }
        Promise<Void> signalled = decisionContext.signalWorkflow(execution.get(), signalName, args);
        if (AsyncInternal.isAsync()) {
            AsyncInternal.setAsyncResult(signalled);
            return;
        }
        try {
            signalled.get();
        } catch (SignalExternalWorkflowException e) {
            // Reset stack to the current one. Otherwise it is very confusing to see a stack of
            // an event handling method.
            e.setStackTrace(Thread.currentThread().getStackTrace());
            throw e;
        }
    }

    private Object queryWorkflow(Method method, QueryMethod queryMethod, Object[] args) {
        throw new UnsupportedOperationException("Query is not supported from workflow to workflow. " +
                "Use activity that perform the query instead.");
    }

    private Object executeChildWorkflow(Method method, WorkflowMethod workflowMethod, Object[] args) {
        String workflowName = workflowMethod.name();
        if (workflowName.isEmpty()) {
            workflowName = InternalUtils.getSimpleName(method);
        }
        byte[] input = dataConverter.toData(args);
        MethodRetry retry = method.getAnnotation(MethodRetry.class);
        ChildWorkflowOptions merged = ChildWorkflowOptions.merge(workflowMethod, retry, options);
        Promise<byte[]> encodedResult = decisionContext.executeChildWorkflow(
                workflowName, merged, input, execution);
        Promise<?> result = encodedResult.thenApply(
                (encoded) -> dataConverter.fromData(encoded, method.getReturnType()));
        if (AsyncInternal.isAsync()) {
            AsyncInternal.setAsyncResult(result);
            return Defaults.defaultValue(method.getReturnType());
        }
        try {
            return result.get();
        } catch (ChildWorkflowException e) {
            // Reset stack to the current one. Otherwise it is very confusing to see a stack of
            // an event handling method.
            e.setStackTrace(Thread.currentThread().getStackTrace());
            throw e;
        }
    }
}
