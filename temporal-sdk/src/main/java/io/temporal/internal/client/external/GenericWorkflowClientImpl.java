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

package io.temporal.internal.client.external;

import static io.temporal.serviceclient.MetricsTag.HISTORY_LONG_POLL_CALL_OPTIONS_KEY;
import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.common.util.concurrent.ListenableFuture;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.grpc.Deadline;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.rpcretry.DefaultStubLongPollRpcRetryOptions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

public final class GenericWorkflowClientImpl implements GenericWorkflowClient {

  private final WorkflowServiceStubs service;
  private final Scope metricsScope;

  public GenericWorkflowClientImpl(WorkflowServiceStubs service, Scope metricsScope) {
    this.service = service;
    this.metricsScope = metricsScope;
  }

  @Override
  public WorkflowExecution start(StartWorkflowExecutionRequest request) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.WORKFLOW_TYPE, request.getWorkflowType().getName())
            .put(MetricsTag.TASK_QUEUE, request.getTaskQueue().getName())
            .build();
    Scope scope = metricsScope.tagged(tags);
    StartWorkflowExecutionResponse result;
    result =
        GrpcRetryer.retryWithResult(
            RpcRetryOptions.newBuilder()
                .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                    .startWorkflowExecution(request));

    return WorkflowExecution.newBuilder()
        .setRunId(result.getRunId())
        .setWorkflowId(request.getWorkflowId())
        .build();
  }

  @Override
  public void signal(SignalWorkflowExecutionRequest request) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(1)
            .put(MetricsTag.SIGNAL_NAME, request.getSignalName())
            .build();
    Scope scope = metricsScope.tagged(tags);
    GrpcRetryer.retry(
        RpcRetryOptions.newBuilder()
            .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .signalWorkflowExecution(request));
  }

  @Override
  public WorkflowExecution signalWithStart(SignalWithStartWorkflowExecutionRequest request) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.WORKFLOW_TYPE, request.getWorkflowType().getName())
            .put(MetricsTag.TASK_QUEUE, request.getTaskQueue().getName())
            .put(MetricsTag.SIGNAL_NAME, request.getSignalName())
            .build();
    Scope scope = metricsScope.tagged(tags);

    SignalWithStartWorkflowExecutionResponse result;
    result =
        GrpcRetryer.retryWithResult(
            RpcRetryOptions.newBuilder()
                .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                    .signalWithStartWorkflowExecution(request));
    return WorkflowExecution.newBuilder()
        .setRunId(result.getRunId())
        .setWorkflowId(request.getWorkflowId())
        .build();
  }

  @Override
  public void requestCancel(RequestCancelWorkflowExecutionRequest request) {
    GrpcRetryer.retry(
        RpcRetryOptions.newBuilder()
            .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .requestCancelWorkflowExecution(request));
  }

  @Override
  public void terminate(TerminateWorkflowExecutionRequest request) {
    GrpcRetryer.retry(
        RpcRetryOptions.newBuilder()
            .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .terminateWorkflowExecution(request));
  }

  @Override
  public GetWorkflowExecutionHistoryResponse longPollHistory(
      GetWorkflowExecutionHistoryRequest request, Deadline deadline) {
    long millisRemaining = deadline.timeRemaining(TimeUnit.MILLISECONDS);
    RpcRetryOptions retryOptions =
        DefaultStubLongPollRpcRetryOptions.getBuilder()
            // TODO rework together with https://github.com/temporalio/sdk-java/issues/1203
            .setExpiration(Duration.ofMillis(millisRemaining))
            .build();
    // TODO to fix https://github.com/temporalio/sdk-java/issues/1177 we need to process
    //  DEADLINE_EXCEEDED
    return GrpcRetryer.retryWithResult(
        retryOptions,
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .withOption(HISTORY_LONG_POLL_CALL_OPTIONS_KEY, true)
                .withDeadline(deadline)
                .getWorkflowExecutionHistory(request));
  }

  @Override
  public CompletableFuture<GetWorkflowExecutionHistoryResponse> longPollHistoryAsync(
      GetWorkflowExecutionHistoryRequest request, Deadline deadline) {
    long millisRemaining = deadline.timeRemaining(TimeUnit.MILLISECONDS);

    RpcRetryOptions retryOptions =
        DefaultStubLongPollRpcRetryOptions.getBuilder()
            // TODO rework together with https://github.com/temporalio/sdk-java/issues/1203
            .setExpiration(Duration.ofMillis(millisRemaining))
            .build();

    return GrpcRetryer.retryWithResultAsync(
        retryOptions,
        () -> {
          CompletableFuture<GetWorkflowExecutionHistoryResponse> result = new CompletableFuture<>();
          ListenableFuture<GetWorkflowExecutionHistoryResponse> resultFuture =
              service
                  .futureStub()
                  .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                  .withOption(HISTORY_LONG_POLL_CALL_OPTIONS_KEY, true)
                  .withDeadline(deadline)
                  .getWorkflowExecutionHistory(request);

          // TODO to fix https://github.com/temporalio/sdk-java/issues/1177 we need to process
          //  DEADLINE_EXCEEDED
          resultFuture.addListener(
              () -> {
                try {
                  result.complete(resultFuture.get());
                } catch (ExecutionException e) {
                  result.completeExceptionally(e.getCause());
                } catch (Exception e) {
                  result.completeExceptionally(e);
                }
              },
              ForkJoinPool.commonPool());
          return result;
        });
  }

  @Override
  public QueryWorkflowResponse query(QueryWorkflowRequest queryParameters) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(1)
            .put(MetricsTag.QUERY_TYPE, queryParameters.getQuery().getQueryType())
            .build();
    Scope scope = metricsScope.tagged(tags);

    return GrpcRetryer.retryWithResult(
        RpcRetryOptions.newBuilder()
            .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .queryWorkflow(queryParameters));
  }
}
