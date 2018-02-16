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
package com.uber.cadence.internal;

import com.uber.cadence.ChildPolicy;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.generic.GenericWorkflowClientExternal;
import com.uber.cadence.internal.generic.QueryWorkflowParameters;
import com.uber.cadence.internal.generic.StartWorkflowExecutionParameters;
import com.uber.cadence.internal.generic.TerminateWorkflowExecutionParameters;
import com.uber.cadence.workflow.SignalExternalWorkflowParameters;

public class DynamicWorkflowClientExternalImpl implements DynamicWorkflowClientExternal {

    protected DataConverter dataConverter;

    private WorkflowOptions schedulingOptions;

    private GenericWorkflowClientExternal genericClient;

    protected WorkflowExecution workflowExecution;

    protected WorkflowType workflowType;

    public DynamicWorkflowClientExternalImpl(String workflowId, WorkflowType workflowType) {
        this(new WorkflowExecution().setWorkflowId(workflowId), workflowType, null, null);
    }

    public DynamicWorkflowClientExternalImpl(WorkflowExecution workflowExecution) {
        this(workflowExecution, null, null, null);
    }

    public DynamicWorkflowClientExternalImpl(String workflowId, WorkflowType workflowType, WorkflowOptions options) {
        this(new WorkflowExecution().setWorkflowId(workflowId), workflowType, options, null, null);
    }

    public DynamicWorkflowClientExternalImpl(WorkflowExecution workflowExecution, WorkflowType workflowType,
            WorkflowOptions options) {
        this(workflowExecution, workflowType, options, null, null);
    }

    private DynamicWorkflowClientExternalImpl(WorkflowExecution workflowExecution, WorkflowType workflowType,
                                              WorkflowOptions options, DataConverter dataConverter) {
        this(workflowExecution, workflowType, options, dataConverter, null);
    }

    DynamicWorkflowClientExternalImpl(WorkflowExecution workflowExecution, WorkflowType workflowType,
                                      WorkflowOptions options, DataConverter dataConverter, GenericWorkflowClientExternal genericClient) {
        this.workflowExecution = workflowExecution;
        this.workflowType = workflowType;
        this.schedulingOptions = options;
        if (dataConverter == null) {
            this.dataConverter = JsonDataConverter.getInstance();
        }
        else {
            this.dataConverter = dataConverter;
        }
        this.genericClient = genericClient;
    }

    public DataConverter getDataConverter() {
        return dataConverter;
    }

    public void setDataConverter(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    public WorkflowOptions getSchedulingOptions() {
        return schedulingOptions;
    }

    public void setSchedulingOptions(WorkflowOptions schedulingOptions) {
        this.schedulingOptions = schedulingOptions;
    }

    public GenericWorkflowClientExternal getGenericClient() {
        return genericClient;
    }

    public void setGenericClient(GenericWorkflowClientExternal genericClient) {
        this.genericClient = genericClient;
    }

    public WorkflowExecution getWorkflowExecution() {
        return workflowExecution;
    }

    public void setWorkflowExecution(WorkflowExecution workflowExecution) {
        this.workflowExecution = workflowExecution;
    }

    public WorkflowType getWorkflowType() {
        return workflowType;
    }

    public void setWorkflowType(WorkflowType workflowType) {
        this.workflowType = workflowType;
    }

    @Override
    public void terminateWorkflowExecution(String reason, byte[] details, ChildPolicy childPolicy) {
        TerminateWorkflowExecutionParameters terminateParameters = new TerminateWorkflowExecutionParameters();
        terminateParameters.setReason(reason);
        terminateParameters.setDetails(details);
        if (childPolicy != null) {
            terminateParameters.setChildPolicy(childPolicy);
        }
        terminateParameters.setWorkflowExecution(workflowExecution);
        genericClient.terminateWorkflowExecution(terminateParameters);
    }

    @Override
    public void requestCancelWorkflowExecution() {
        genericClient.requestCancelWorkflowExecution(workflowExecution);
    }

    @Override
    public void startWorkflowExecution(Object[] arguments) throws WorkflowExecutionAlreadyStartedException {
        startWorkflowExecution(arguments, null);
    }

    @Override
    public void startWorkflowExecution(Object[] arguments, WorkflowOptions startOptionsOverride) throws WorkflowExecutionAlreadyStartedException {
        if (workflowType == null) {
            throw new IllegalStateException("Required property workflowType is null");
        }
        if (workflowExecution == null) {
            throw new IllegalStateException("wokflowExecution is null");
        }
        else if (workflowExecution.getWorkflowId() == null) {
            throw new IllegalStateException("wokflowId is null");
        }
        StartWorkflowExecutionParameters parameters = new StartWorkflowExecutionParameters();
        parameters.setWorkflowType(workflowType);
        parameters.setWorkflowId(workflowExecution.getWorkflowId());
        byte[] input = dataConverter.toData(arguments);
        parameters.setInput(input);
        parameters = parameters.createStartWorkflowExecutionParametersFromOptions(schedulingOptions, startOptionsOverride);
        WorkflowExecution newExecution = genericClient.startWorkflow(parameters);
        String runId = newExecution.getRunId();
        workflowExecution.setRunId(runId);
    }

    @Override
    public void signalWorkflowExecution(String signalName, Object[] arguments) {
        SignalExternalWorkflowParameters signalParameters = new SignalExternalWorkflowParameters();
        signalParameters.setRunId(workflowExecution.getRunId());
        signalParameters.setWorkflowId(workflowExecution.getWorkflowId());
        signalParameters.setSignalName(signalName);
        byte[] input = dataConverter.toData(arguments);
        signalParameters.setInput(input);
        genericClient.signalWorkflowExecution(signalParameters);
    }

    @Override
    public <T> T queryWorkflowExecution(String queryType, Object[] arguments, Class<T> returnType) {
        QueryWorkflowParameters p = new QueryWorkflowParameters();
        p.setWorkflowId(workflowExecution.getWorkflowId());
        p.setRunId(workflowExecution.getRunId());
        p.setQueryType(queryType);
        p.setInput(dataConverter.toData(arguments));
        byte[] result = genericClient.queryWorkflow(p);
        return dataConverter.fromData(result, returnType);
    }
}
