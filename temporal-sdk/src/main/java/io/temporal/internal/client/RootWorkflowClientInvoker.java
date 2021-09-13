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
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.internal.common.SignalWithStartWorkflowExecutionParameters;
import io.temporal.internal.external.GenericWorkflowClientExternal;
import java.lang.reflect.Type;
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
  public WorkflowSignalOutput signal(WorkflowSignalInput input) {
    SignalWorkflowExecutionRequest.Builder request =
        SignalWorkflowExecutionRequest.newBuilder()
            .setSignalName(input.getSignalName())
            .setWorkflowExecution(input.getWorkflowExecution());

    if (clientOptions.getIdentity() != null) {
      request.setIdentity(clientOptions.getIdentity());
    }
    if (clientOptions.getNamespace() != null) {
      request.setNamespace(clientOptions.getNamespace());
    }
    Optional<Payloads> inputArgs =
        clientOptions.getDataConverter().toPayloads(input.getArguments());
    inputArgs.ifPresent(request::setInput);
    genericClient.signal(request.build());
    return new WorkflowSignalOutput();
  }

  @Override
  public WorkflowSignalWithStartOutput signalWithStart(WorkflowSignalWithStartInput input) {
    StartWorkflowExecutionRequest request =
        requestsHelper.newStartWorkflowExecutionRequest(input.getWorkflowStartInput());
    Optional<Payloads> signalInput =
        clientOptions.getDataConverter().toPayloads(input.getSignalArguments());
    SignalWithStartWorkflowExecutionParameters p =
        new SignalWithStartWorkflowExecutionParameters(request, input.getSignalName(), signalInput);
    return new WorkflowSignalWithStartOutput(
        new WorkflowStartOutput(genericClient.signalWithStart(p)));
  }

  @Override
  public <R> GetResultOutput<R> getResult(GetResultInput<R> input) throws TimeoutException {
    Optional<Payloads> resultValue =
        WorkflowClientLongPollHelper.getWorkflowExecutionResult(
            genericClient.getService(),
            genericClient.getNamespace(),
            input.getWorkflowExecution(),
            input.getWorkflowType(),
            metricsScope,
            clientOptions.getDataConverter(),
            input.getTimeout(),
            input.getTimeoutUnit());
    return new GetResultOutput<>(
        convertResultPayloads(resultValue, input.getResultClass(), input.getResultType()));
  }

  @Override
  public <R> GetResultAsyncOutput<R> getResultAsync(GetResultInput<R> input) {
    CompletableFuture<Optional<Payloads>> resultValue =
        WorkflowClientLongPollAsyncHelper.getWorkflowExecutionResultAsync(
            genericClient.getService(),
            genericClient.getNamespace(),
            input.getWorkflowExecution(),
            input.getWorkflowType(),
            input.getTimeout(),
            input.getTimeoutUnit(),
            clientOptions.getDataConverter());
    return new GetResultAsyncOutput<>(
        resultValue.thenApply(
            payloads ->
                convertResultPayloads(payloads, input.getResultClass(), input.getResultType())));
  }

  @Override
  public <R> QueryOutput<R> query(QueryInput<R> input) {
    WorkflowQuery.Builder query = WorkflowQuery.newBuilder().setQueryType(input.getQueryType());
    Optional<Payloads> inputArgs =
        clientOptions.getDataConverter().toPayloads(input.getArguments());
    inputArgs.ifPresent(query::setQueryArgs);
    QueryWorkflowRequest request =
        QueryWorkflowRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setExecution(
                WorkflowExecution.newBuilder()
                    .setWorkflowId(input.getWorkflowExecution().getWorkflowId())
                    .setRunId(input.getWorkflowExecution().getRunId()))
            .setQuery(query)
            .setQueryRejectCondition(clientOptions.getQueryRejectCondition())
            .build();

    QueryWorkflowResponse result;
    result = genericClient.query(request);

    boolean queryRejected = result.hasQueryRejected();
    WorkflowExecutionStatus rejectStatus =
        queryRejected ? result.getQueryRejected().getStatus() : null;
    Optional<Payloads> queryResult =
        result.hasQueryResult() ? Optional.of(result.getQueryResult()) : Optional.empty();
    R resultValue =
        convertResultPayloads(queryResult, input.getResultClass(), input.getResultType());
    return new QueryOutput<>(rejectStatus, resultValue);
  }

  @Override
  public CancelOutput cancel(CancelInput input) {
    RequestCancelWorkflowExecutionRequest.Builder request =
        RequestCancelWorkflowExecutionRequest.newBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setWorkflowExecution(input.getWorkflowExecution())
            .setNamespace(clientOptions.getNamespace())
            .setIdentity(clientOptions.getIdentity());
    genericClient.requestCancel(request.build());
    return new CancelOutput();
  }

  @Override
  public TerminateOutput terminate(TerminateInput input) {
    TerminateWorkflowExecutionRequest.Builder request =
        TerminateWorkflowExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setWorkflowExecution(input.getWorkflowExecution())
            .setReason(input.getReason());
    Optional<Payloads> payloads = clientOptions.getDataConverter().toPayloads(input.getDetails());
    payloads.ifPresent(request::setDetails);
    genericClient.terminate(request.build());
    return new TerminateOutput();
  }

  private <R> R convertResultPayloads(
      Optional<Payloads> resultValue, Class<R> resultClass, Type resultType) {
    return clientOptions.getDataConverter().fromPayloads(0, resultValue, resultClass, resultType);
  }
}
