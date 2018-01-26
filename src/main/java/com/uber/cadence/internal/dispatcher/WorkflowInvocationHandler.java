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

import com.google.common.base.Defaults;
import com.uber.cadence.DataConverter;
import com.uber.cadence.StartWorkflowOptions;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionAlreadyStartedException;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.common.FlowHelpers;
import com.uber.cadence.generic.GenericWorkflowClientExternal;
import com.uber.cadence.generic.QueryWorkflowParameters;
import com.uber.cadence.generic.StartWorkflowExecutionParameters;
import com.uber.cadence.worker.GenericWorkflowClientExternalImpl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

class WorkflowInvocationHandler implements InvocationHandler {

    private static final ThreadLocal<AtomicReference<WorkflowExternalResult>> asyncResult = new ThreadLocal<>();
    private final GenericWorkflowClientExternal genericClient;
    private final StartWorkflowOptions options;
    private final DataConverter dataConverter;
    WorkflowExecution execution;

    public static void initAsyncInvocation() {
        if (asyncResult.get() != null) {
            throw new IllegalStateException("already in async invocation");
        }
        asyncResult.set(new AtomicReference<>());
    }

    public static WorkflowExternalResult getAsyncInvocationResult() {
        try {
            AtomicReference<WorkflowExternalResult> reference = asyncResult.get();
            if (reference == null) {
                throw new IllegalStateException("initAsyncInvocation wasn't called");
            }
            WorkflowExternalResult result = reference.get();
            if (result == null) {
                throw new IllegalStateException("async result wasn't set");
            }
            return result;
        } finally {
            asyncResult.remove();
        }
    }

    public WorkflowInvocationHandler(GenericWorkflowClientExternalImpl genericClient, WorkflowExecution execution, DataConverter dataConverter) {
        if (execution == null || execution.getWorkflowId() == null || execution.getWorkflowId().isEmpty()) {
            throw new IllegalArgumentException("null or empty workflowId");
        }
        this.genericClient = genericClient;
        this.execution = execution;
        this.options = null;
        this.dataConverter = dataConverter;
    }

    WorkflowInvocationHandler(GenericWorkflowClientExternal genericClient, StartWorkflowOptions options, DataConverter dataConverter) {
        this.genericClient = genericClient;
        this.options = options;
        this.dataConverter = dataConverter;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
        QueryMethod queryMethod = method.getAnnotation(QueryMethod.class);
        if (workflowMethod != null) {
            if (queryMethod != null) {
                throw new IllegalArgumentException(method.getName() + " annotated with both @WorkflowMethod and @QueryMethod");
            }
            if (execution != null) {
                throw new IllegalStateException("Already started: " + execution);
            }
            return startWorkflow(method, workflowMethod, args);
        }
        if (queryMethod != null) {
            if (execution == null) {
                throw new IllegalStateException("Workflow not started yet");
            }
            return queryWorkflow(method, queryMethod, args);
        }
        throw new IllegalArgumentException(method.getName() + " is not annotated with @WorkflowMethod or @QueryMethod");
    }

    private Object queryWorkflow(Method method, QueryMethod queryMethod, Object[] args) {
        String queryType = queryMethod.name();
        if (queryType.isEmpty()) {
            queryType = FlowHelpers.getSimpleName(method);
        }
        QueryWorkflowParameters p = new QueryWorkflowParameters();
        p.setInput(dataConverter.toData(args));
        p.setQueryType(queryType);
        p.setRunId(execution.getRunId());
        p.setWorkflowId(execution.getWorkflowId());
        byte[] queryResult= genericClient.queryWorkflow(p);
        return dataConverter.fromData(queryResult, method.getReturnType());
    }

    private Object startWorkflow(Method method, WorkflowMethod workflowMethod, Object[] args) throws WorkflowExecutionAlreadyStartedException, java.util.concurrent.TimeoutException, InterruptedException {
        String workflowName = workflowMethod.name();
        if (workflowName.isEmpty()) {
            workflowName = FlowHelpers.getSimpleName(method);
        }
        StartWorkflowExecutionParameters parameters = new StartWorkflowExecutionParameters();
        parameters.setExecutionStartToCloseTimeoutSeconds(options.getExecutionStartToCloseTimeoutSeconds());
        parameters.setTaskList(options.getTaskList());
        parameters.setTaskStartToCloseTimeoutSeconds(options.getTaskStartToCloseTimeoutSeconds());
        parameters.setWorkflowType(new WorkflowType().setName(workflowName));
        parameters.setWorkflowId(UUID.randomUUID().toString()); // TODO: specifying id.
        byte[] input = dataConverter.toData(args);
        parameters.setInput(input);
        // TODO: Return workflow result or its execution through async.
        execution = genericClient.startWorkflow(parameters);
        // TODO: Wait for result using long poll Cadence API.
        WorkflowService.Iface service = genericClient.getService();
        String domain = genericClient.getDomain();
        WorkflowExternalResult result = new WorkflowExternalResult(
                service,
                domain,
                execution,
                options.getExecutionStartToCloseTimeoutSeconds(),
                dataConverter,
                method.getReturnType());
        AtomicReference<WorkflowExternalResult> async = asyncResult.get();
        if (async != null) {
            async.set(result);
            return Defaults.defaultValue(method.getReturnType());
        }
        return result.getResult();
    }
}
