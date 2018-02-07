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

import com.uber.cadence.SignalWorkflowExecutionRequest;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionCompletedEventAttributes;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.client.WorkflowExternalResult;
import com.uber.cadence.internal.DataConverter;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import org.apache.thrift.TException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class WorkflowExternalResultImpl<R> implements WorkflowExternalResult<R> {
    private final WorkflowService.Iface service;
    private final String domain;
    private final WorkflowExecution execution;
    private final DataConverter dataConverter;
    private final Class<R> returnType;

    public WorkflowExternalResultImpl(WorkflowService.Iface service,
                                      String domain,
                                      WorkflowExecution execution,
                                      DataConverter dataConverter,
                                      Class<R> returnType) {
        this.service = service;
        this.domain = domain;
        this.execution = execution;
        this.dataConverter = dataConverter;
        this.returnType = returnType;
    }

    @Override
    public WorkflowExecution getExecution() {
        return execution;
    }

    @Override
    public <G> void signal(String name, G input) {
        SignalWorkflowExecutionRequest signalRequest = new SignalWorkflowExecutionRequest();
        signalRequest.setInput(dataConverter.toData(new Object[]{input}));
        signalRequest.setDomain(domain);
        signalRequest.setSignalName(name);
        signalRequest.setWorkflowExecution(execution);
        try {
            service.SignalWorkflowExecution(signalRequest);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public R getResult() {
        try {
            return getResult(0, TimeUnit.MICROSECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Unexpected", e);
        }
    }

    @Override
    public R getResult(long timeout, TimeUnit unit) throws TimeoutException {
        WorkflowExecutionCompletedEventAttributes result =
                WorkflowExecutionUtils.getWorkflowExecutionResult(service, domain, execution, timeout, unit);
        byte[] resultValue = result.getResult();
        if (resultValue == null) {
            return null;
        }
        return dataConverter.fromData(resultValue, returnType);
    }
}
