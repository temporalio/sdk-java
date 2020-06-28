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

import static io.temporal.internal.common.HeaderUtils.toHeaderGrpc;

import com.google.common.base.Strings;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.common.v1.Header;
import io.temporal.common.v1.Memo;
import io.temporal.common.v1.Payload;
import io.temporal.common.v1.Payloads;
import io.temporal.common.v1.RetryPolicy;
import io.temporal.common.v1.SearchAttributes;
import io.temporal.common.v1.WorkflowExecution;
import io.temporal.internal.common.GrpcRetryer;
import io.temporal.internal.common.OptionsUtils;
import io.temporal.internal.common.RetryParameters;
import io.temporal.internal.common.SignalWithStartWorkflowExecutionParameters;
import io.temporal.internal.common.StartWorkflowExecutionParameters;
import io.temporal.internal.common.TerminateWorkflowExecutionParameters;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.QueryWorkflowParameters;
import io.temporal.internal.replay.SignalExternalWorkflowParameters;
import io.temporal.query.v1.WorkflowQuery;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.taskqueue.v1.TaskQueue;
import io.temporal.workflowservice.v1.QueryWorkflowRequest;
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
    try {
      return startWorkflowInternal(startParameters);
    } finally {
      // TODO: can probably cache this
      Map<String, String> tags =
          new ImmutableMap.Builder<String, String>(3)
              .put(MetricsTag.WORKFLOW_TYPE, startParameters.getWorkflowType().getName())
              .put(MetricsTag.TASK_QUEUE, startParameters.getTaskQueue())
              .put(MetricsTag.NAMESPACE, namespace)
              .build();
      metricsScope.tagged(tags).counter(MetricsType.WORKFLOW_START_COUNTER).inc(1);
    }
  }

  private WorkflowExecution startWorkflowInternal(
      StartWorkflowExecutionParameters startParameters) {
    StartWorkflowExecutionRequest.Builder request =
        StartWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setRequestId(UUID.randomUUID().toString())
            .setIdentity(identity);
    Optional<Payloads> input = startParameters.getInput();
    if (input.isPresent()) {
      request.setInput(input.get());
    }
    request.setWorkflowRunTimeoutSeconds(startParameters.getWorkflowRunTimeoutSeconds());
    request.setWorkflowExecutionTimeoutSeconds(
        startParameters.getWorkflowExecutionTimeoutSeconds());
    request.setWorkflowTaskTimeoutSeconds(startParameters.getWorkflowTaskTimeoutSeconds());
    if (startParameters.getWorkflowIdReusePolicy() != null) {
      request.setWorkflowIdReusePolicy(startParameters.getWorkflowIdReusePolicy());
    }
    String taskQueue = startParameters.getTaskQueue();
    if (taskQueue != null && !taskQueue.isEmpty()) {
      request.setTaskQueue(TaskQueue.newBuilder().setName(taskQueue).build());
    }
    String workflowId = startParameters.getWorkflowId();
    if (workflowId == null) {
      workflowId = UUID.randomUUID().toString();
    }
    request.setWorkflowId(workflowId);
    request.setWorkflowType(startParameters.getWorkflowType());
    RetryParameters retryParameters = startParameters.getRetryParameters();
    if (retryParameters != null) {
      RetryPolicy retryPolicy = toRetryPolicy(retryParameters);
      request.setRetryPolicy(retryPolicy);
    }
    if (!Strings.isNullOrEmpty(startParameters.getCronSchedule())) {
      request.setCronSchedule(startParameters.getCronSchedule());
    }
    Memo memo = toMemoGrpc(startParameters.getMemo());
    if (memo != null) {
      request.setMemo(memo);
    }
    SearchAttributes searchAttributes =
        toSearchAttributesGrpc(startParameters.getSearchAttributes());
    if (searchAttributes != null) {
      request.setSearchAttributes(searchAttributes);
    }
    Header header = toHeaderGrpc(startParameters.getContext());
    if (header != null) {
      request.setHeader(header);
    }

    StartWorkflowExecutionResponse result;
    result =
        GrpcRetryer.retryWithResult(
            GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
            () -> service.blockingStub().startWorkflowExecution(request.build()));

    return WorkflowExecution.newBuilder()
        .setRunId(result.getRunId())
        .setWorkflowId(request.getWorkflowId())
        .build();
  }

  private Memo toMemoGrpc(Map<String, Payload> memo) {
    if (memo == null || memo.isEmpty()) {
      return null;
    }
    Memo.Builder builder = Memo.newBuilder();
    for (Map.Entry<String, Payload> item : memo.entrySet()) {
      builder.putFields(item.getKey(), item.getValue());
    }
    return builder.build();
  }

  private SearchAttributes toSearchAttributesGrpc(Map<String, Payload> searchAttributes) {
    if (searchAttributes == null || searchAttributes.isEmpty()) {
      return null;
    }
    SearchAttributes.Builder builder = SearchAttributes.newBuilder();
    for (Map.Entry<String, Payload> item : searchAttributes.entrySet()) {
      builder.putIndexedFields(item.getKey(), item.getValue());
    }
    return builder.build();
  }

  private RetryPolicy toRetryPolicy(RetryParameters retryParameters) {
    return RetryPolicy.newBuilder()
        .setBackoffCoefficient(retryParameters.getBackoffCoefficient())
        .setInitialIntervalInSeconds(retryParameters.getInitialIntervalInSeconds())
        .setMaximumAttempts(retryParameters.getMaximumAttempts())
        .setMaximumIntervalInSeconds(retryParameters.getMaximumIntervalInSeconds())
        .addAllNonRetryableErrorTypes(retryParameters.getNonRetriableErrorTypes())
        .build();
  }

  @Override
  public void signalWorkflowExecution(SignalExternalWorkflowParameters signalParameters) {
    SignalWorkflowExecutionRequest.Builder request =
        SignalWorkflowExecutionRequest.newBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setIdentity(identity)
            .setNamespace(
                signalParameters.getNamespace() == null
                    ? namespace
                    : signalParameters.getNamespace())
            .setSignalName(signalParameters.getSignalName())
            .setWorkflowExecution(
                WorkflowExecution.newBuilder()
                    .setRunId(OptionsUtils.safeGet(signalParameters.getRunId()))
                    .setWorkflowId(signalParameters.getWorkflowId()));

    Optional<Payloads> input = signalParameters.getInput();
    if (input.isPresent()) {
      request.setInput(input.get());
    }
    GrpcRetryer.retry(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () -> service.blockingStub().signalWorkflowExecution(request.build()));
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
              .put(MetricsTag.TASK_QUEUE, parameters.getStartParameters().getTaskQueue())
              .put(MetricsTag.NAMESPACE, namespace)
              .build();
      metricsScope.tagged(tags).counter(MetricsType.WORKFLOW_SIGNAL_WITH_START_COUNTER).inc(1);
    }
  }

  private WorkflowExecution signalWithStartWorkflowInternal(
      SignalWithStartWorkflowExecutionParameters parameters, String identity) {
    StartWorkflowExecutionParameters startParameters = parameters.getStartParameters();

    SignalWithStartWorkflowExecutionRequest.Builder request =
        SignalWithStartWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setRequestId(UUID.randomUUID().toString())
            .setIdentity(identity)
            .setSignalName(parameters.getSignalName())
            .setWorkflowRunTimeoutSeconds(startParameters.getWorkflowRunTimeoutSeconds())
            .setWorkflowExecutionTimeoutSeconds(
                startParameters.getWorkflowExecutionTimeoutSeconds())
            .setWorkflowTaskTimeoutSeconds(startParameters.getWorkflowTaskTimeoutSeconds())
            .setWorkflowType(startParameters.getWorkflowType());

    Optional<Payloads> signalInput = parameters.getSignalInput();
    if (signalInput.isPresent()) {
      request.setSignalInput(signalInput.get());
    }
    Optional<Payloads> input = startParameters.getInput();
    if (input.isPresent()) {
      request.setInput(input.get());
    }
    if (startParameters.getWorkflowIdReusePolicy() != null) {
      request.setWorkflowIdReusePolicy(startParameters.getWorkflowIdReusePolicy());
    }
    String taskQueue = startParameters.getTaskQueue();
    if (taskQueue != null && !taskQueue.isEmpty()) {
      request.setTaskQueue(TaskQueue.newBuilder().setName(taskQueue).build());
    }
    String workflowId = startParameters.getWorkflowId();
    if (workflowId == null) {
      workflowId = UUID.randomUUID().toString();
    }
    request.setWorkflowId(workflowId);
    RetryParameters retryParameters = startParameters.getRetryParameters();
    if (retryParameters != null) {
      RetryPolicy retryPolicy = toRetryPolicy(retryParameters);
      request.setRetryPolicy(retryPolicy);
    }
    if (!Strings.isNullOrEmpty(startParameters.getCronSchedule())) {
      request.setCronSchedule(startParameters.getCronSchedule());
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
  public void requestCancelWorkflowExecution(WorkflowExecution execution) {
    RequestCancelWorkflowExecutionRequest request =
        RequestCancelWorkflowExecutionRequest.newBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setIdentity(identity)
            .setNamespace(namespace)
            .setWorkflowExecution(execution)
            .build();
    GrpcRetryer.retry(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () -> service.blockingStub().requestCancelWorkflowExecution(request));
  }

  @Override
  public QueryWorkflowResponse queryWorkflow(QueryWorkflowParameters queryParameters) {
    WorkflowQuery.Builder query =
        WorkflowQuery.newBuilder().setQueryType(queryParameters.getQueryType());
    Optional<Payloads> input = queryParameters.getInput();
    if (input.isPresent()) {
      query.setQueryArgs(input.get());
    }
    QueryWorkflowRequest request =
        QueryWorkflowRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(
                WorkflowExecution.newBuilder()
                    .setWorkflowId(queryParameters.getWorkflowId())
                    .setRunId(OptionsUtils.safeGet(queryParameters.getRunId())))
            .setQuery(query)
            .setQueryRejectCondition(queryParameters.getQueryRejectCondition())
            .build();
    return GrpcRetryer.retryWithResult(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () -> service.blockingStub().queryWorkflow(request));
  }

  @Override
  public String generateUniqueId() {
    String workflowId = UUID.randomUUID().toString();
    return workflowId;
  }

  @Override
  public void terminateWorkflowExecution(TerminateWorkflowExecutionParameters terminateParameters) {
    TerminateWorkflowExecutionRequest.Builder request =
        TerminateWorkflowExecutionRequest.newBuilder()
            .setIdentity(identity)
            .setWorkflowExecution(terminateParameters.getWorkflowExecution())
            .setNamespace(namespace)
            .setReason(terminateParameters.getReason());
    Payloads details = terminateParameters.getDetails();
    if (details != null) {
      request.setDetails(details);
    }
    GrpcRetryer.retry(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () -> service.blockingStub().terminateWorkflowExecution(request.build()));
  }
}
