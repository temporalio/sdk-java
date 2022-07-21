/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
import java.util.Map;
import java.util.concurrent.*;
import javax.annotation.Nonnull;

public final class GenericWorkflowClientImpl implements GenericWorkflowClient {

  private final WorkflowServiceStubs service;
  private final Scope metricsScope;
  private final GrpcRetryer grpcRetryer;
  private final GrpcRetryer.GrpcRetryerOptions grpcRetryerOptions;

  public GenericWorkflowClientImpl(WorkflowServiceStubs service, Scope metricsScope) {
    this.service = service;
    this.metricsScope = metricsScope;
    RpcRetryOptions rpcRetryOptions =
        RpcRetryOptions.newBuilder()
            .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions());
    this.grpcRetryer = new GrpcRetryer(service.getServerCapabilities());
    this.grpcRetryerOptions = new GrpcRetryer.GrpcRetryerOptions(rpcRetryOptions, null);
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
        grpcRetryer.retryWithResult(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                    .startWorkflowExecution(request),
            grpcRetryerOptions);

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
    grpcRetryer.retry(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .signalWorkflowExecution(request),
        grpcRetryerOptions);
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
        grpcRetryer.retryWithResult(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                    .signalWithStartWorkflowExecution(request),
            grpcRetryerOptions);
    return WorkflowExecution.newBuilder()
        .setRunId(result.getRunId())
        .setWorkflowId(request.getWorkflowId())
        .build();
  }

  @Override
  public void requestCancel(RequestCancelWorkflowExecutionRequest request) {
    grpcRetryer.retry(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .requestCancelWorkflowExecution(request),
        grpcRetryerOptions);
  }

  @Override
  public void terminate(TerminateWorkflowExecutionRequest request) {
    grpcRetryer.retry(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .terminateWorkflowExecution(request),
        grpcRetryerOptions);
  }

  @Override
  public GetWorkflowExecutionHistoryResponse longPollHistory(
      @Nonnull GetWorkflowExecutionHistoryRequest request, @Nonnull Deadline deadline) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .withOption(HISTORY_LONG_POLL_CALL_OPTIONS_KEY, true)
                .withDeadline(deadline)
                .getWorkflowExecutionHistory(request),
        new GrpcRetryer.GrpcRetryerOptions(DefaultStubLongPollRpcRetryOptions.INSTANCE, deadline));
  }

  @Override
  public CompletableFuture<GetWorkflowExecutionHistoryResponse> longPollHistoryAsync(
      @Nonnull GetWorkflowExecutionHistoryRequest request, @Nonnull Deadline deadline) {
    return grpcRetryer.retryWithResultAsync(
        () -> {
          CompletableFuture<GetWorkflowExecutionHistoryResponse> result = new CompletableFuture<>();
          ListenableFuture<GetWorkflowExecutionHistoryResponse> resultFuture =
              service
                  .futureStub()
                  .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                  .withOption(HISTORY_LONG_POLL_CALL_OPTIONS_KEY, true)
                  .withDeadline(deadline)
                  .getWorkflowExecutionHistory(request);

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
        },
        new GrpcRetryer.GrpcRetryerOptions(DefaultStubLongPollRpcRetryOptions.INSTANCE, deadline));
  }

  @Override
  public QueryWorkflowResponse query(QueryWorkflowRequest queryParameters) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(1)
            .put(MetricsTag.QUERY_TYPE, queryParameters.getQuery().getQueryType())
            .build();
    Scope scope = metricsScope.tagged(tags);

    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .queryWorkflow(queryParameters),
        grpcRetryerOptions);
  }
}
