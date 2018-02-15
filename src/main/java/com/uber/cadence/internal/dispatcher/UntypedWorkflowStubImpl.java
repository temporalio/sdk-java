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
import com.uber.cadence.client.UntypedWorkflowStub;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.error.CheckedExceptionWrapper;
import com.uber.cadence.internal.StartWorkflowOptions;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.generic.GenericWorkflowClientExternal;
import com.uber.cadence.internal.generic.QueryWorkflowParameters;
import com.uber.cadence.internal.generic.StartWorkflowExecutionParameters;
import com.uber.cadence.workflow.SignalExternalWorkflowParameters;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class UntypedWorkflowStubImpl implements UntypedWorkflowStub {
    private final GenericWorkflowClientExternal genericClient;
    private final DataConverter dataConverter;
    private final String workflowType;
    private AtomicReference<WorkflowExecution> execution = new AtomicReference<>();
    private final StartWorkflowOptions options;

    public UntypedWorkflowStubImpl(GenericWorkflowClientExternal genericClient, DataConverter dataConverter,
                                   WorkflowExecution execution) {
        this.genericClient = genericClient;
        this.dataConverter = dataConverter;
        this.workflowType = null;
        this.execution.set(execution);
        this.options = null;
    }

    public UntypedWorkflowStubImpl(GenericWorkflowClientExternal genericClient, DataConverter dataConverter,
                                   String workflowType, StartWorkflowOptions options) {
        this.genericClient = genericClient;
        this.dataConverter = dataConverter;
        this.workflowType = workflowType;
        this.options = options;
    }

    @Override
    public void signal(String signalName, Object... input) {
        checkStarted();
        SignalExternalWorkflowParameters p = new SignalExternalWorkflowParameters();
        p.setInput(dataConverter.toData(input));
        p.setSignalName(signalName);
        p.setWorkflowId(execution.get().getWorkflowId());
        // TODO: Deal with signalling started workflow only, when requested
        // Commented out to support signalling workflows that called continue as new.
//        p.setRunId(execution.getRunId());
        genericClient.signalWorkflowExecution(p);
    }

    @Override
    public WorkflowExecution start(Object... args) {
        if (options == null) {
            throw new IllegalStateException("UntypedWorkflowStub wasn't created through " +
                    "CadenceClient::newUntypedWorkflowStub(String workflowType, StartWorkflowOptions options)");
        }
        if (execution.get() != null) {
            throw new IllegalStateException("already started for execution=" + execution.get());
        }
        StartWorkflowExecutionParameters p = new StartWorkflowExecutionParameters();
        p.setTaskStartToCloseTimeoutSeconds(options.getTaskStartToCloseTimeoutSeconds());
        p.setWorkflowId(options.getWorkflowId());
        p.setExecutionStartToCloseTimeoutSeconds(options.getExecutionStartToCloseTimeoutSeconds());
        p.setInput(dataConverter.toData(args));
        p.setWorkflowType(new WorkflowType().setName(workflowType));
        p.setTaskList(options.getTaskList());
        p.setChildPolicy(options.getChildPolicy());
        execution.set(genericClient.startWorkflow(p));
        return execution.get();
    }

    @Override
    public <R> R getResult(Class<R> returnType) {
        try {
            return getResult(Long.MAX_VALUE, TimeUnit.MILLISECONDS, returnType);
        } catch (TimeoutException e) {
            throw CheckedExceptionWrapper.wrap(e);
        }
    }

    @Override
    public <R> R getResult(long timeout, TimeUnit unit, Class<R> returnType) throws TimeoutException {
        checkStarted();
        WorkflowExecutionCompletedEventAttributes result =
                WorkflowExecutionUtils.getWorkflowExecutionResult(genericClient.getService(), genericClient.getDomain(),
                        execution.get(), timeout, unit);
        byte[] resultValue = result.getResult();
        if (resultValue == null) {
            return null;
        }
        return dataConverter.fromData(resultValue, returnType);
    }


    @Override
    public <R> R query(String queryType, Class<R> returnType, Object... args) {
        checkStarted();
        QueryWorkflowParameters p = new QueryWorkflowParameters();
        p.setInput(dataConverter.toData(args));
        p.setQueryType(queryType);
        p.setWorkflowId(execution.get().getWorkflowId());
        byte[] result = genericClient.queryWorkflow(p);
        return dataConverter.fromData(result, returnType);
    }

    @Override
    public void cancel() {
        if (execution.get() == null || execution.get().getWorkflowId() == null) {
            return;
        }
        genericClient.requestCancelWorkflowExecution(execution.get());
    }

    private void checkStarted() {
        if (execution.get() == null || execution.get().getWorkflowId() == null) {
            throw new IllegalStateException("Null workflowId. Was workflow started?");
        }
    }
}
