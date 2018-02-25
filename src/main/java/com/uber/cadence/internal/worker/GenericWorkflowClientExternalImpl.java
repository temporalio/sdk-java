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
package com.uber.cadence.internal.worker;

import com.uber.cadence.QueryWorkflowRequest;
import com.uber.cadence.QueryWorkflowResponse;
import com.uber.cadence.RequestCancelWorkflowExecutionRequest;
import com.uber.cadence.SignalWorkflowExecutionRequest;
import com.uber.cadence.StartWorkflowExecutionRequest;
import com.uber.cadence.StartWorkflowExecutionResponse;
import com.uber.cadence.TaskList;
import com.uber.cadence.TerminateWorkflowExecutionRequest;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionAlreadyStartedError;
import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.internal.generic.GenericWorkflowClientExternal;
import com.uber.cadence.internal.generic.QueryWorkflowParameters;
import com.uber.cadence.internal.generic.StartWorkflowExecutionParameters;
import com.uber.cadence.internal.generic.TerminateWorkflowExecutionParameters;
import com.uber.cadence.workflow.SignalExternalWorkflowParameters;
import org.apache.thrift.TException;

import java.util.UUID;

public class GenericWorkflowClientExternalImpl implements GenericWorkflowClientExternal {

    private final String domain;

    private final WorkflowService.Iface service;

    public GenericWorkflowClientExternalImpl(WorkflowService.Iface service, String domain) {
        this.service = service;
        this.domain = domain;
    }

    public String getDomain() {
        return domain;
    }

    @Override
    public WorkflowService.Iface getService() {
        return service;
    }

    @Override
    public WorkflowExecution startWorkflow(StartWorkflowExecutionParameters startParameters) throws WorkflowExecutionAlreadyStartedError {
        StartWorkflowExecutionRequest request = new StartWorkflowExecutionRequest();
        request.setDomain(domain);

        request.setInput(startParameters.getInput());
        request.setExecutionStartToCloseTimeoutSeconds(startParameters.getExecutionStartToCloseTimeoutSeconds());
        request.setTaskStartToCloseTimeoutSeconds(startParameters.getTaskStartToCloseTimeoutSeconds());
        request.setWorkflowIdReusePolicy(startParameters.getWorkflowIdReusePolicy());
        String taskList = startParameters.getTaskList();
        if (taskList != null && !taskList.isEmpty()) {
            TaskList tl = new TaskList();
            tl.setName(taskList);
            request.setTaskList(tl);
        }
        String workflowId = startParameters.getWorkflowId();
        if (workflowId == null) {
            workflowId = UUID.randomUUID().toString();
        }
        request.setWorkflowId(workflowId);
        request.setWorkflowType(startParameters.getWorkflowType());

//        if(startParameters.getChildPolicy() != null) {
//            request.setChildPolicy(startParameters.getChildPolicy());
//        }

        StartWorkflowExecutionResponse result;
        try {
            result = service.StartWorkflowExecution(request);
        } catch (WorkflowExecutionAlreadyStartedError e) {
            throw e;
        } catch (TException e) {
            throw new RuntimeException(e);
        }
        WorkflowExecution execution = new WorkflowExecution();
        execution.setRunId(result.getRunId());
        execution.setWorkflowId(request.getWorkflowId());

        return execution;
    }

    @Override
    public void signalWorkflowExecution(SignalExternalWorkflowParameters signalParameters) {
        SignalWorkflowExecutionRequest request = new SignalWorkflowExecutionRequest();
        request.setDomain(domain);

        request.setInput(signalParameters.getInput());
        request.setSignalName(signalParameters.getSignalName());
        WorkflowExecution execution = new WorkflowExecution();
        execution.setRunId(signalParameters.getRunId());
        execution.setWorkflowId(signalParameters.getWorkflowId());
        request.setWorkflowExecution(execution);
        try {
            service.SignalWorkflowExecution(request);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void requestCancelWorkflowExecution(WorkflowExecution execution) {
        RequestCancelWorkflowExecutionRequest request = new RequestCancelWorkflowExecutionRequest();
        request.setDomain(domain);
        request.setWorkflowExecution(execution);
        try {
            service.RequestCancelWorkflowExecution(request);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] queryWorkflow(QueryWorkflowParameters queryParameters) {
        QueryWorkflowRequest request = new QueryWorkflowRequest();
        request.setDomain(domain);
        WorkflowExecution execution = new WorkflowExecution();
        execution.setWorkflowId(queryParameters.getWorkflowId()).setRunId(queryParameters.getRunId());
        request.setExecution(execution);
        WorkflowQuery query = new WorkflowQuery();
        query.setQueryArgs(queryParameters.getInput());
        query.setQueryType(queryParameters.getQueryType());
        request.setQuery(query);
        try {
            QueryWorkflowResponse response = service.QueryWorkflow(request);
            return response.getQueryResult();
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String generateUniqueId() {
        String workflowId = UUID.randomUUID().toString();
        return workflowId;
    }

    @Override
    public void terminateWorkflowExecution(TerminateWorkflowExecutionParameters terminateParameters) {
        TerminateWorkflowExecutionRequest request = new TerminateWorkflowExecutionRequest();
        WorkflowExecution workflowExecution = terminateParameters.getWorkflowExecution();
        request.setWorkflowExecution(terminateParameters.getWorkflowExecution());
        request.setDomain(domain);
        request.setDetails(terminateParameters.getDetails());
        request.setReason(terminateParameters.getReason());
//        request.setChildPolicy(terminateParameters.getChildPolicy());
        try {
            service.TerminateWorkflowExecution(request);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }
}
