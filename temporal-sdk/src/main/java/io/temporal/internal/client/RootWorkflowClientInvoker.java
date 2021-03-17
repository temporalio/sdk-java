/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.internal.client;

import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.*;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.internal.common.SignalWithStartWorkflowExecutionParameters;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.external.GenericWorkflowClientExternal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

public class RootWorkflowClientInvoker implements WorkflowClientCallsInterceptor {
  private final GenericWorkflowClientExternal genericClient;
  private final WorkflowClientOptions clientOptions;
  private final Scope metricsScope;
  private final RootWorkflowClientHelper requestsHelper;

  public RootWorkflowClientInvoker(
      GenericWorkflowClientExternal genericClient,
      WorkflowClientOptions clientOptions,
      Scope metricsScope) {
    this.genericClient = genericClient;
    this.clientOptions = clientOptions;
    this.metricsScope = metricsScope;
    this.requestsHelper = new RootWorkflowClientHelper(clientOptions);
  }

  @Override
  public WorkflowStartOutput start(WorkflowStartInput input) {
    StartWorkflowExecutionRequest request = requestsHelper.newStartWorkflowExecutionRequest(input);
    return new WorkflowStartOutput(genericClient.start(request));
  }

  @Override
  public void signal(WorkflowSignalInput input) {
    SignalWorkflowExecutionRequest.Builder request =
        SignalWorkflowExecutionRequest.newBuilder()
            .setSignalName(input.getSignalName())
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(input.getWorkflowId()));

    if (clientOptions.getIdentity() != null) {
      request.setIdentity(clientOptions.getIdentity());
    }
    if (clientOptions.getNamespace() != null) {
      request.setNamespace(clientOptions.getNamespace());
    }
    Optional<Payloads> inputArgs =
        clientOptions.getDataConverter().toPayloads(input.getArguments());
    if (inputArgs.isPresent()) {
      request.setInput(inputArgs.get());
    }
    genericClient.signal(request.build());
  }

  @Override
  public WorkflowStartOutput signalWithStart(WorkflowStartWithSignalInput input) {
    StartWorkflowExecutionRequest request =
        requestsHelper.newStartWorkflowExecutionRequest(input.getWorkflowStartInput());
    Optional<Payloads> signalInput =
        clientOptions.getDataConverter().toPayloads(input.getWorkflowSignalInput().getArguments());
    SignalWithStartWorkflowExecutionParameters p =
        new SignalWithStartWorkflowExecutionParameters(
            request, input.getWorkflowSignalInput().getSignalName(), signalInput);
    return new WorkflowStartOutput(genericClient.signalWithStart(p));
  }

  @Override
  public <R> GetResultOutput<R> getResult(GetResultInput<R> input) throws TimeoutException {
    Optional<Payloads> resultValue =
        WorkflowExecutionUtils.getWorkflowExecutionResult(
            genericClient.getService(),
            genericClient.getNamespace(),
            input.getWorkflowExecution(),
            input.getWorkflowType(),
            metricsScope,
            clientOptions.getDataConverter(),
            input.getTimeout(),
            input.getTimeoutUnit());
    return new GetResultOutput<>(convertResultPayloads(resultValue, input));
  }

  @Override
  public <R> GetResultAsyncOutput<R> getResultAsync(GetResultInput<R> input) {
    CompletableFuture<Optional<Payloads>> resultValue =
        WorkflowExecutionUtils.getWorkflowExecutionResultAsync(
            genericClient.getService(),
            genericClient.getNamespace(),
            input.getWorkflowExecution(),
            input.getWorkflowType(),
            input.getTimeout(),
            input.getTimeoutUnit(),
            clientOptions.getDataConverter());
    return new GetResultAsyncOutput<>(
        resultValue.thenApply(payloads -> convertResultPayloads(payloads, input)));
  }

  private <R> R convertResultPayloads(Optional<Payloads> resultValue, GetResultInput<R> input) {
    return clientOptions
        .getDataConverter()
        .fromPayloads(0, resultValue, input.getResultClass(), input.getResultType());
  }
}
