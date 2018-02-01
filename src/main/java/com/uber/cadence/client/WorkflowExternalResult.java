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
package com.uber.cadence.client;

import com.uber.cadence.internal.DataConverter;
import com.uber.cadence.SignalWorkflowExecutionRequest;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionCompletedEventAttributes;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import org.apache.thrift.TException;

import java.util.concurrent.TimeoutException;

public final class WorkflowExternalResult<R> {
    private final WorkflowService.Iface service;
    private final String domain;
    private final WorkflowExecution execution;
    private final int executionStartToCloseTimeoutSeconds;
    private final DataConverter dataConverter;
    private final Class<R> returnType;

    public WorkflowExternalResult(WorkflowService.Iface service,
                                  String domain,
                                  WorkflowExecution execution,
                                  int executionStartToCloseTimeoutSeconds,
                                  DataConverter dataConverter,
                                  Class<R> returnType) {
        this.service = service;
        this.domain = domain;
        this.execution = execution;
        this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
        this.dataConverter = dataConverter;
        this.returnType = returnType;
    }

    public WorkflowExecution getExecution() {
        return execution;
    }

    public <G> void signal(String name, G input) {
        SignalWorkflowExecutionRequest signalRequest = new SignalWorkflowExecutionRequest();
        signalRequest.setInput(dataConverter.toData(new Object[] {input}));
        signalRequest.setDomain(domain);
        signalRequest.setSignalName(name);
        signalRequest.setWorkflowExecution(execution);
        try {
            service.SignalWorkflowExecution(signalRequest);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }

    public R getResult() throws TimeoutException, InterruptedException {
        WorkflowExecutionCompletedEventAttributes result =
                WorkflowExecutionUtils.waitForWorkflowExecutionResult(
                        service, domain, execution, executionStartToCloseTimeoutSeconds + 1);
        return dataConverter.fromData(result.getResult(), returnType);
    }
}
