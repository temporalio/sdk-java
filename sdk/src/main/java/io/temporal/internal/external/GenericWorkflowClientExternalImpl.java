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

package io.temporal.internal.external;

import static io.temporal.internal.metrics.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.QueryWorkflowRequest;
import io.temporal.api.workflowservice.v1.QueryWorkflowResponse;
import io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest;
import io.temporal.internal.common.GrpcRetryer;
import io.temporal.internal.common.SignalWithStartWorkflowExecutionParameters;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class GenericWorkflowClientExternalImpl implements GenericWorkflowClientExternal {

  private final String namespace;
  private final WorkflowServiceStubs service;
  private final Scope metricsScope;
  private final String identity;

  public GenericWorkflowClientExternalImpl(
      WorkflowServiceStubs service, String namespace, String identity, Scope metricsScope) {
    this.service = service;
    this.namespace = namespace;
    this.identity = identity;
    this.metricsScope = metricsScope;
  }

  @Override
  public String getNamespace() {
    return namespace;
  }

  @Override
  public WorkflowServiceStubs getService() {
    return service;
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
            GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
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
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .signalWorkflowExecution(request));
  }

  @Override
  public WorkflowExecution signalWithStart(SignalWithStartWorkflowExecutionParameters parameters) {
    StartWorkflowExecutionRequest startParameters = parameters.getStartParameters();

    SignalWithStartWorkflowExecutionRequest.Builder request =
        SignalWithStartWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setRequestId(generateUniqueId())
            .setIdentity(identity)
            .setSignalName(parameters.getSignalName())
            .setWorkflowRunTimeout(startParameters.getWorkflowRunTimeout())
            .setWorkflowExecutionTimeout(startParameters.getWorkflowExecutionTimeout())
            .setWorkflowTaskTimeout(startParameters.getWorkflowTaskTimeout())
            .setWorkflowType(startParameters.getWorkflowType())
            .setWorkflowIdReusePolicy(startParameters.getWorkflowIdReusePolicy())
            .setCronSchedule(startParameters.getCronSchedule());

    Optional<Payloads> signalInput = parameters.getSignalInput();
    if (signalInput.isPresent()) {
      request.setSignalInput(signalInput.get());
    }

    if (startParameters.hasInput()) {
      request.setInput(startParameters.getInput());
    }

    if (startParameters.hasTaskQueue()) {
      request.setTaskQueue(startParameters.getTaskQueue());
    }

    String workflowId = startParameters.getWorkflowId();
    if (workflowId.isEmpty()) {
      workflowId = generateUniqueId();
    }
    request.setWorkflowId(workflowId);

    if (startParameters.hasRetryPolicy()) {
      request.setRetryPolicy(startParameters.getRetryPolicy());
    }

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
            GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                    .signalWithStartWorkflowExecution(request.build()));
    return WorkflowExecution.newBuilder()
        .setRunId(result.getRunId())
        .setWorkflowId(request.getWorkflowId())
        .build();
  }

  @Override
  public void requestCancel(RequestCancelWorkflowExecutionRequest request) {
    GrpcRetryer.retry(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .requestCancelWorkflowExecution(request));
  }

  @Override
  public void terminate(TerminateWorkflowExecutionRequest request) {
    GrpcRetryer.retry(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .terminateWorkflowExecution(request));
  }

  @Override
  public QueryWorkflowResponse query(QueryWorkflowRequest queryParameters) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(1)
            .put(MetricsTag.QUERY_TYPE, queryParameters.getQuery().getQueryType())
            .build();
    Scope scope = metricsScope.tagged(tags);

    return GrpcRetryer.retryWithResult(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .queryWorkflow(queryParameters));
  }

  @Override
  public String generateUniqueId() {
    return UUID.randomUUID().toString();
  }
}
