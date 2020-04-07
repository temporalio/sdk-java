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

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
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
import io.temporal.proto.common.Header;
import io.temporal.proto.common.Memo;
import io.temporal.proto.common.RetryPolicy;
import io.temporal.proto.common.SearchAttributes;
import io.temporal.proto.common.TaskList;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.common.WorkflowQuery;
import io.temporal.proto.enums.QueryConsistencyLevel;
import io.temporal.proto.workflowservice.QueryWorkflowRequest;
import io.temporal.proto.workflowservice.QueryWorkflowResponse;
import io.temporal.proto.workflowservice.RequestCancelWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.SignalWithStartWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.SignalWithStartWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.SignalWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.StartWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.StartWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.TerminateWorkflowExecutionRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.HashMap;
import java.util.Map;
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
              .put(MetricsTag.TASK_LIST, startParameters.getTaskList())
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
    if (startParameters.getInput() != null) {
      request.setInput(ByteString.copyFrom(startParameters.getInput()));
    }
    request.setExecutionStartToCloseTimeoutSeconds(
        (int) startParameters.getExecutionStartToCloseTimeoutSeconds());
    request.setTaskStartToCloseTimeoutSeconds(
        (int) startParameters.getTaskStartToCloseTimeoutSeconds());
    request.setWorkflowIdReusePolicy(startParameters.getWorkflowIdReusePolicy());
    String taskList = startParameters.getTaskList();
    if (taskList != null && !taskList.isEmpty()) {
      request.setTaskList(TaskList.newBuilder().setName(taskList).build());
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

  private Memo toMemoGrpc(Map<String, byte[]> memo) {
    if (memo == null || memo.isEmpty()) {
      return null;
    }

    Map<String, ByteString> fields = new HashMap<>();
    for (Map.Entry<String, byte[]> item : memo.entrySet()) {
      fields.put(item.getKey(), ByteString.copyFrom(item.getValue()));
    }
    return Memo.newBuilder().putAllFields(fields).build();
  }

  private SearchAttributes toSearchAttributesGrpc(Map<String, byte[]> searchAttributes) {
    if (searchAttributes == null || searchAttributes.isEmpty()) {
      return null;
    }

    Map<String, ByteString> fields = new HashMap<>();
    for (Map.Entry<String, byte[]> item : searchAttributes.entrySet()) {
      fields.put(item.getKey(), ByteString.copyFrom(item.getValue()));
    }
    return SearchAttributes.newBuilder().putAllIndexedFields(fields).build();
  }

  private Header toHeaderGrpc(Map<String, byte[]> headers) {
    if (headers == null || headers.isEmpty()) {
      return null;
    }
    Map<String, ByteString> fields = new HashMap<>();
    for (Map.Entry<String, byte[]> item : headers.entrySet()) {
      fields.put(item.getKey(), ByteString.copyFrom(item.getValue()));
    }
    return Header.newBuilder().putAllFields(fields).build();
  }

  private RetryPolicy toRetryPolicy(RetryParameters retryParameters) {
    return RetryPolicy.newBuilder()
        .setBackoffCoefficient(retryParameters.getBackoffCoefficient())
        .setExpirationIntervalInSeconds(retryParameters.getExpirationIntervalInSeconds())
        .setInitialIntervalInSeconds(retryParameters.getInitialIntervalInSeconds())
        .setMaximumAttempts(retryParameters.getMaximumAttempts())
        .setMaximumIntervalInSeconds(retryParameters.getMaximumIntervalInSeconds())
        .addAllNonRetriableErrorReasons(retryParameters.getNonRetriableErrorReasons())
        .build();
  }

  @Override
  public void signalWorkflowExecution(SignalExternalWorkflowParameters signalParameters) {
    SignalWorkflowExecutionRequest request =
        SignalWorkflowExecutionRequest.newBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setIdentity(identity)
            .setNamespace(
                signalParameters.getNamespace() == null
                    ? namespace
                    : signalParameters.getNamespace())
            .setInput(OptionsUtils.toByteString(signalParameters.getInput()))
            .setSignalName(signalParameters.getSignalName())
            .setWorkflowExecution(
                WorkflowExecution.newBuilder()
                    .setRunId(OptionsUtils.safeGet(signalParameters.getRunId()))
                    .setWorkflowId(signalParameters.getWorkflowId()))
            .build();
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
              .put(MetricsTag.TASK_LIST, parameters.getStartParameters().getTaskList())
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
            .setExecutionStartToCloseTimeoutSeconds(
                (int) startParameters.getExecutionStartToCloseTimeoutSeconds())
            .setTaskStartToCloseTimeoutSeconds(
                (int) startParameters.getTaskStartToCloseTimeoutSeconds())
            .setWorkflowType(startParameters.getWorkflowType());

    byte[] signalInput = parameters.getSignalInput();
    if (signalInput != null) {
      request.setSignalInput(ByteString.copyFrom(signalInput));
    }
    if (startParameters.getInput() != null) {
      request.setInput(ByteString.copyFrom(startParameters.getInput()));
    }
    request.setWorkflowIdReusePolicy(startParameters.getWorkflowIdReusePolicy());
    String taskList = startParameters.getTaskList();
    if (taskList != null && !taskList.isEmpty()) {
      request.setTaskList(TaskList.newBuilder().setName(taskList).build());
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
    QueryWorkflowRequest request =
        QueryWorkflowRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(
                WorkflowExecution.newBuilder()
                    .setWorkflowId(queryParameters.getWorkflowId())
                    .setRunId(OptionsUtils.safeGet(queryParameters.getRunId())))
            .setQuery(
                WorkflowQuery.newBuilder()
                    .setQueryArgs(OptionsUtils.toByteString(queryParameters.getInput()))
                    .setQueryType(queryParameters.getQueryType()))
            .setQueryRejectCondition(queryParameters.getQueryRejectCondition())
            .setQueryConsistencyLevel(
                QueryConsistencyLevel
                    .QueryConsistencyLevelEventual) // TODO: Configurable and strong
            .build();
    return GrpcRetryer.retryWithResult(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () -> {
          try {
            QueryWorkflowResponse result = service.blockingStub().queryWorkflow(request);
            System.out.println(result);
            return result;
          } catch (RuntimeException e) {
            throw e;
          }
        });
  }

  @Override
  public String generateUniqueId() {
    String workflowId = UUID.randomUUID().toString();
    return workflowId;
  }

  @Override
  public void terminateWorkflowExecution(TerminateWorkflowExecutionParameters terminateParameters) {
    TerminateWorkflowExecutionRequest request =
        TerminateWorkflowExecutionRequest.newBuilder()
            .setIdentity(identity)
            .setWorkflowExecution(terminateParameters.getWorkflowExecution())
            .setNamespace(namespace)
            .setDetails(ByteString.copyFrom(terminateParameters.getDetails()))
            .setReason(terminateParameters.getReason())
            .build();
    GrpcRetryer.retry(
        GrpcRetryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
        () -> service.blockingStub().terminateWorkflowExecution(request));
  }
}
