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
package com.uber.cadence;

import com.uber.cadence.common.WorkflowExecutionUtils;
import org.apache.thrift.TException;

import java.util.concurrent.CancellationException;

class ManualActivityCompletionClientImpl extends ManualActivityCompletionClient {

    private final WorkflowService.Iface service;

    private final byte[] taskToken;

    private final DataConverter dataConverter;

    public ManualActivityCompletionClientImpl(WorkflowService.Iface service, byte[] taskToken, DataConverter dataConverter) {
        this.service = service;
        this.taskToken = taskToken;
        this.dataConverter = dataConverter;
    }

    @Override
    public void complete(Object result) {
        RespondActivityTaskCompletedRequest request = new RespondActivityTaskCompletedRequest();
        byte[] convertedResult = dataConverter.toData(result);
        request.setResult(convertedResult);
        request.setTaskToken(taskToken);
        try {
            service.RespondActivityTaskCompleted(request);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void fail(Throwable failure) {
        RespondActivityTaskFailedRequest request = new RespondActivityTaskFailedRequest();
        byte[] convertedFailure = dataConverter.toData(failure);
        request.setReason(WorkflowExecutionUtils.truncateReason(failure.getMessage()));
        request.setDetails(convertedFailure);
        request.setTaskToken(taskToken);
        try {
            service.RespondActivityTaskFailed(request);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void recordHeartbeat(byte[] details) throws CancellationException {
        RecordActivityTaskHeartbeatRequest request = new RecordActivityTaskHeartbeatRequest();
        request.setDetails(details);
        request.setTaskToken(taskToken);
        RecordActivityTaskHeartbeatResponse status = null;
        try {
            status = service.RecordActivityTaskHeartbeat(request);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
        if (status.isCancelRequested()) {
            throw new CancellationException();
        }
    }

    @Override
    public void reportCancellation(byte[] details) {
        RespondActivityTaskCanceledRequest request = new RespondActivityTaskCanceledRequest();
        request.setDetails(details);
        request.setTaskToken(taskToken);
        try {
            service.RespondActivityTaskCanceled(request);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }

}
