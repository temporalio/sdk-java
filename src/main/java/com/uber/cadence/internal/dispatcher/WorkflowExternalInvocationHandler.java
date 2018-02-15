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

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionCompletedEventAttributes;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.error.CheckedExceptionWrapper;
import com.uber.cadence.internal.StartWorkflowOptions;
import com.uber.cadence.internal.common.FlowHelpers;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.generic.GenericWorkflowClientExternal;
import com.uber.cadence.internal.generic.QueryWorkflowParameters;
import com.uber.cadence.internal.generic.StartWorkflowExecutionParameters;
import com.uber.cadence.internal.worker.GenericWorkflowClientExternalImpl;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.SignalExternalWorkflowParameters;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.WorkflowMethod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class WorkflowExternalInvocationHandler implements InvocationHandler {

    private static final ThreadLocal<AtomicReference<WorkflowExecution>> asyncResult = new ThreadLocal<>();
    private final GenericWorkflowClientExternal genericClient;
    private final StartWorkflowOptions options;
    private final DataConverter dataConverter;
    private final AtomicReference<WorkflowExecution> execution = new AtomicReference<>();

    public static void initAsyncInvocation() {
        if (asyncResult.get() != null) {
            throw new IllegalStateException("already in asyncStart invocation");
        }
        asyncResult.set(new AtomicReference<>());
    }

    public static WorkflowExecution getAsyncInvocationResult() {
        try {
            AtomicReference<WorkflowExecution> reference = asyncResult.get();
            if (reference == null) {
                throw new IllegalStateException("initAsyncInvocation wasn't called");
            }
            WorkflowExecution result = reference.get();
            if (result == null) {
                throw new IllegalStateException("Only methods of a stub created through CadenceClient.newWorkflowStub " +
                        "can be used as a parameter to the asyncStart.");
            }
            return result;
        } finally {
            asyncResult.remove();
        }
    }

    public WorkflowExternalInvocationHandler(GenericWorkflowClientExternalImpl genericClient, WorkflowExecution execution, DataConverter dataConverter) {
        if (execution == null || execution.getWorkflowId() == null || execution.getWorkflowId().isEmpty()) {
            throw new IllegalArgumentException("null or empty workflowId");
        }
        this.genericClient = genericClient;
        this.execution.set(execution);
        this.options = null;
        this.dataConverter = dataConverter;
    }

    public WorkflowExternalInvocationHandler(GenericWorkflowClientExternal genericClient, StartWorkflowOptions options, DataConverter dataConverter) {
        this.genericClient = genericClient;
        this.options = options;
        this.dataConverter = dataConverter;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
        QueryMethod queryMethod = method.getAnnotation(QueryMethod.class);
        SignalMethod signalMethod = method.getAnnotation(SignalMethod.class);
        int count = (workflowMethod == null ? 0 : 1) + (queryMethod == null ? 0 : 1) + (signalMethod == null ? 0 : 1);
        if (count > 1) {
            throw new IllegalArgumentException(method + " must contain at most one annotation " +
                    "from @WorkflowMethod, @QueryMethod or @SignalMethod");
        }
        if (workflowMethod != null) {
            if (execution.get() != null) {
                throw new IllegalStateException("Already started: " + execution);
            }
            return startWorkflow(method, workflowMethod, args);
        }
        if (execution.get() == null) {
            throw new IllegalStateException("Workflow not started yet");
        }
        if (queryMethod != null) {
            return queryWorkflow(method, queryMethod, args);
        }
        if (signalMethod != null) {
            signalWorkflow(method, signalMethod, args);
            return null;
        }
        throw new IllegalArgumentException(method + " is not annotated with @WorkflowMethod or @QueryMethod");
    }

    private void signalWorkflow(Method method, SignalMethod signalMethod, Object[] args) {
        if (method.getReturnType() != Void.TYPE) {
            throw new IllegalArgumentException("Signal method must have void return type: " + method);
        }

        String signalName = signalMethod.name();
        if (signalName.isEmpty()) {
            signalName = FlowHelpers.getSimpleName(method);
        }
        SignalExternalWorkflowParameters signalParameters = new SignalExternalWorkflowParameters();
        WorkflowExecution exe = execution.get();
        signalParameters.setRunId(exe.getRunId());
        signalParameters.setWorkflowId(exe.getWorkflowId());
        signalParameters.setSignalName(signalName);
        byte[] input = dataConverter.toData(args);
        signalParameters.setInput(input);
        genericClient.signalWorkflowExecution(signalParameters);
    }

    private Object queryWorkflow(Method method, QueryMethod queryMethod, Object[] args) {
        if (method.getReturnType() == Void.TYPE) {
            throw new IllegalArgumentException("Query method cannot have void return type: " + method);
        }
        String queryType = queryMethod.name();
        if (queryType.isEmpty()) {
            queryType = FlowHelpers.getSimpleName(method);
        }
        QueryWorkflowParameters p = new QueryWorkflowParameters();
        p.setInput(dataConverter.toData(args));
        p.setQueryType(queryType);
        p.setRunId(execution.get().getRunId());
        p.setWorkflowId(execution.get().getWorkflowId());
        byte[] queryResult = genericClient.queryWorkflow(p);
        return dataConverter.fromData(queryResult, method.getReturnType());
    }

    private Object startWorkflow(Method method, WorkflowMethod workflowMethod, Object[] args) {
        String workflowName = workflowMethod.name();
        if (workflowName.isEmpty()) {
            workflowName = FlowHelpers.getSimpleName(method);
        }
        StartWorkflowExecutionParameters parameters = new StartWorkflowExecutionParameters();
        parameters.setExecutionStartToCloseTimeoutSeconds(options.getExecutionStartToCloseTimeoutSeconds());
        parameters.setTaskList(options.getTaskList());
        parameters.setTaskStartToCloseTimeoutSeconds(options.getTaskStartToCloseTimeoutSeconds());
        parameters.setWorkflowType(new WorkflowType().setName(workflowName));
        if (options.getWorkflowId() == null) {
            parameters.setWorkflowId(UUID.randomUUID().toString());
        } else {
            parameters.setWorkflowId(options.getWorkflowId());
        }
        byte[] input = dataConverter.toData(args);
        parameters.setInput(input);
        execution.set(genericClient.startWorkflow(parameters));
        AtomicReference<WorkflowExecution> async = asyncResult.get();
        if (async != null) {
            async.set(execution.get());
            return null;
        }
        try {
            WorkflowExecutionCompletedEventAttributes result =
                    WorkflowExecutionUtils.getWorkflowExecutionResult(genericClient.getService(), genericClient.getDomain(),
                            execution.get(), options.getExecutionStartToCloseTimeoutSeconds(), TimeUnit.SECONDS);
            byte[] resultValue = result.getResult();
            if (resultValue == null) {
                return null;
            }
            return dataConverter.fromData(resultValue, method.getReturnType());
        } catch (Exception e) {
            throw CheckedExceptionWrapper.wrap(e);
        }
    }
}
