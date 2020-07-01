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

import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.common.v1.Payloads;
import io.temporal.common.v1.WorkflowExecution;
import io.temporal.internal.common.GrpcRetryer;
import io.temporal.internal.common.SignalWithStartWorkflowExecutionParameters;
import io.temporal.internal.common.StartWorkflowExecutionParameters;
import io.temporal.internal.common.TerminateWorkflowExecutionParameters;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.QueryWorkflowParameters;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflowservice.v1.QueryWorkflowResponse;
import io.temporal.workflowservice.v1.RequestCancelWorkflowExecutionRequest;
import io.temporal.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.workflowservice.v1.SignalWithStartWorkflowExecutionResponse;
import io.temporal.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.workflowservice.v1.TerminateWorkflowExecutionRequest;
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
  public WorkflowExecution startWorkflow(StartWorkflowExecutionParameters startParameters) {
    StartWorkflowExecutionRequest request = startParameters.getRequest();
    try {
      return startWorkflowInternal(startParameters);
    } finally {
      // TODO: can probably cache this
      Map<String, String> tags =
          new ImmutableMap.Builder<String, String>(3)
              .put(MetricsTag.WORKFLOW_TYPE, request.getWorkflowType().getName())
              .put(MetricsTag.TASK_QUEUE, request.getTaskQueue().getName())
              .put(MetricsTag.NAMESPACE, namespace)
              .build();
      metricsScope.tagged(tags).counter(MetricsType.WORKFLOW_START_COUNTER).inc(1);
    }
  }

  private WorkflowExecution startWorkflowInternal(
      StartWorkflowExecutionParameters startParameters) {
    StartWorkflowExecutionRequest request = startParameters.getRequest();
    StartWorkflowExecutionResponse result;
    result =
        GrpcRetryer.retryWithResult(
            GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
            () -> service.blockingStub().startWorkflowExecution(request));

    return WorkflowExecution.newBuilder()
        .setRunId(result.getRunId())
        .setWorkflowId(request.getWorkflowId())
        .build();
  }

  @Override
  public void signalWorkflowExecution(SignalWorkflowExecutionRequest request) {
    GrpcRetryer.retry(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () -> service.blockingStub().signalWorkflowExecution(request));
  }

  @Override
  public WorkflowExecution signalWithStartWorkflowExecution(
      SignalWithStartWorkflowExecutionParameters parameters) {
    try {
      return signalWithStartWorkflowInternal(parameters, identity);
    } finally {
      Map<String, String> tags =
          new ImmutableMap.Builder<String, String>(3)
              .put(
                  MetricsTag.WORKFLOW_TYPE,
                  parameters.getStartParameters().getWorkflowType().getName())
              .put(MetricsTag.TASK_QUEUE, parameters.getStartParameters().getTaskQueue().getName())
              .put(MetricsTag.NAMESPACE, namespace)
              .build();
      metricsScope.tagged(tags).counter(MetricsType.WORKFLOW_SIGNAL_WITH_START_COUNTER).inc(1);
    }
  }

  private WorkflowExecution signalWithStartWorkflowInternal(
      SignalWithStartWorkflowExecutionParameters parameters, String identity) {
    StartWorkflowExecutionRequest startParameters = parameters.getStartParameters();

    SignalWithStartWorkflowExecutionRequest.Builder request =
        SignalWithStartWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setRequestId(generateUniqueId())
            .setIdentity(identity)
            .setSignalName(parameters.getSignalName())
            .setWorkflowRunTimeoutSeconds(startParameters.getWorkflowRunTimeoutSeconds())
            .setWorkflowExecutionTimeoutSeconds(
                startParameters.getWorkflowExecutionTimeoutSeconds())
            .setWorkflowTaskTimeoutSeconds(startParameters.getWorkflowTaskTimeoutSeconds())
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

    SignalWithStartWorkflowExecutionResponse result;
    result =
        GrpcRetryer.retryWithResult(
            GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
            () -> service.blockingStub().signalWithStartWorkflowExecution(request.build()));
    return WorkflowExecution.newBuilder()
        .setRunId(result.getRunId())
        .setWorkflowId(request.getWorkflowId())
        .build();
  }

  @Override
  public void requestCancelWorkflowExecution(CancelWorkflowParameters parameters) {
    RequestCancelWorkflowExecutionRequest request = parameters.getRequest();
    GrpcRetryer.retry(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () -> {
          service.blockingStub().requestCancelWorkflowExecution(request);
        });
  }

  @Override
  public void terminateWorkflowExecution(TerminateWorkflowExecutionParameters terminateParameters) {
    TerminateWorkflowExecutionRequest.Builder request = terminateParameters.getRequest();
    GrpcRetryer.retry(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () -> service.blockingStub().terminateWorkflowExecution(request.build()));
  }

  @Override
  public QueryWorkflowResponse queryWorkflow(QueryWorkflowParameters queryParameters) {
    return GrpcRetryer.retryWithResult(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () -> service.blockingStub().queryWorkflow(queryParameters.getRequest()));
  }

  @Override
  public String generateUniqueId() {
    return UUID.randomUUID().toString();
  }
}
