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
import com.uber.cadence.WorkflowType;
import com.uber.cadence.client.UntypedWorkflowStub;
import com.uber.cadence.client.WorkflowExternalResult;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.StartWorkflowOptions;
import com.uber.cadence.internal.generic.GenericWorkflowClientExternal;
import com.uber.cadence.internal.generic.QueryWorkflowParameters;
import com.uber.cadence.internal.generic.StartWorkflowExecutionParameters;
import com.uber.cadence.workflow.SignalExternalWorkflowParameters;

public class UntypedWorkflowStubImpl implements UntypedWorkflowStub {
    private final GenericWorkflowClientExternal genericClient;
    private final DataConverter dataConverter;
    private final String workflowType;
    private WorkflowExecution execution;
    private final StartWorkflowOptions options;

    public UntypedWorkflowStubImpl(GenericWorkflowClientExternal genericClient, DataConverter dataConverter,
                                   WorkflowExecution execution) {
        this.genericClient = genericClient;
        this.dataConverter = dataConverter;
        this.workflowType = null;
        this.execution = execution;
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
        if (execution == null || execution.getWorkflowId() == null) {
            throw new IllegalStateException("Null workflowId. Was workflow started?");
        }
        SignalExternalWorkflowParameters p = new SignalExternalWorkflowParameters();
        p.setInput(dataConverter.toData(input));
        p.setSignalName(signalName);
        p.setWorkflowId(execution.getWorkflowId());
        // TODO: Deal with signalling started workflow only, when requested
        // Commented out to support signalling workflows that called continue as new.
//        p.setRunId(execution.getRunId());
        genericClient.signalWorkflowExecution(p);
    }

    @Override
    public <R> WorkflowExternalResult<R> execute(Class<R> returnType, Object... args) {
        if (options == null) {
            throw new IllegalStateException("UntypedWorkflowStub wasn't created through " +
                    "CadenceClient::newUntypedWorkflowStub(String workflowType, StartWorkflowOptions options)");
        }
        StartWorkflowExecutionParameters p = new StartWorkflowExecutionParameters();
        if (execution != null) {
            p.setWorkflowId(execution.getWorkflowId());
        }
        p.setTaskStartToCloseTimeoutSeconds(options.getTaskStartToCloseTimeoutSeconds());
        p.setWorkflowId(options.getWorkflowId());
        p.setExecutionStartToCloseTimeoutSeconds(options.getExecutionStartToCloseTimeoutSeconds());
        p.setInput(dataConverter.toData(args));
        p.setWorkflowType(new WorkflowType().setName(workflowType));
        p.setTaskList(options.getTaskList());
        p.setChildPolicy(options.getChildPolicy());
        execution = genericClient.startWorkflow(p);
        return new WorkflowExternalResultImpl<>(genericClient.getService(), genericClient.getDomain(), execution,
                dataConverter, returnType);
    }

    @Override
    public <R> R query(String queryType, Class<R> returnType, Object... args) {
        if (execution == null || execution.getWorkflowId() == null) {
            throw new IllegalStateException("Null workflowId. Was workflow started?");
        }
        QueryWorkflowParameters p = new QueryWorkflowParameters();
        p.setInput(dataConverter.toData(args));
        p.setQueryType(queryType);
        p.setWorkflowId(execution.getWorkflowId());
        byte[] result = genericClient.queryWorkflow(p);
        return dataConverter.fromData(result, returnType);
    }

    @Override
    public void cancel() {
        if (execution == null || execution.getWorkflowId() == null) {
            throw new IllegalStateException("Null workflowId. Was workflow started?");
        }
        genericClient.requestCancelWorkflowExecution(execution);
    }
}
