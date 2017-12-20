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
package com.uber.cadence.worker;

import com.uber.cadence.WorkflowExecutionAlreadyStartedException;
import com.uber.cadence.generic.GenericWorkflowClientExternal;
import com.uber.cadence.generic.SignalExternalWorkflowParameters;
import com.uber.cadence.generic.StartWorkflowExecutionParameters;
import com.uber.cadence.generic.TerminateWorkflowExecutionParameters;
import com.uber.cadence.RequestCancelWorkflowExecutionRequest;
import com.uber.cadence.SignalWorkflowExecutionRequest;
import com.uber.cadence.StartWorkflowExecutionRequest;
import com.uber.cadence.StartWorkflowExecutionResponse;
import com.uber.cadence.TaskList;
import com.uber.cadence.TerminateWorkflowExecutionRequest;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionAlreadyStartedError;
import com.uber.cadence.WorkflowService;
import org.apache.thrift.TException;

import java.util.UUID;

public class GenericWorkflowClientExternalImpl implements GenericWorkflowClientExternal {

    private final String domain;

    private final WorkflowService.Iface service;

    public GenericWorkflowClientExternalImpl(WorkflowService.Iface service, String domain) {
        this.service = service;
        this.domain = domain;
    }

    @Override
    public WorkflowExecution startWorkflow(StartWorkflowExecutionParameters startParameters) throws WorkflowExecutionAlreadyStartedException{
        StartWorkflowExecutionRequest request = new StartWorkflowExecutionRequest();
        request.setDomain(domain);

        request.setInput(startParameters.getInput());
        request.setExecutionStartToCloseTimeoutSeconds(startParameters.getExecutionStartToCloseTimeout());
        request.setTaskStartToCloseTimeoutSeconds(startParameters.getTaskStartToCloseTimeoutSeconds());
        String taskList = startParameters.getTaskList();
        if (taskList != null && !taskList.isEmpty()) {
            TaskList tl = new TaskList();
            tl.setName(taskList);
            request.setTaskList(tl);
        }
        request.setWorkflowId(startParameters.getWorkflowId());
        request.setWorkflowType(startParameters.getWorkflowType());

//        if(startParameters.getChildPolicy() != null) {
//            request.setChildPolicy(startParameters.getChildPolicy());
//        }

        StartWorkflowExecutionResponse result = null;
        try {
            result = service.StartWorkflowExecution(request);
        } catch (WorkflowExecutionAlreadyStartedError e) {
            throw new WorkflowExecutionAlreadyStartedException(startParameters.getWorkflowId(), e);
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
    public String generateUniqueId() {
        String workflowId = UUID.randomUUID().toString();
        return workflowId;
    }

    @Override
    public byte[] getWorkflowState(WorkflowExecution execution) {
        throw new UnsupportedOperationException("latestExecutionContext is not part of DescribeWorkflowExecutionResponse yet");
//        DescribeWorkflowExecutionRequest request = new DescribeWorkflowExecutionRequest();
//        request.setDomain(domain);
//        request.setExecution(execution);
//        DescribeWorkflowExecutionResponse details = null;
//        try {
//            details = service.DescribeWorkflowExecution(request);
//        } catch (TException e) {
//            throw new RuntimeException(e);
//        }
//        return details.getGetLatestExecutionContext();
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
