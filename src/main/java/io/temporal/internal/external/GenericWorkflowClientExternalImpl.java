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

package io.temporal.internal.external;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.*;
import io.temporal.internal.common.*;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.QueryWorkflowParameters;
import io.temporal.internal.replay.SignalExternalWorkflowParameters;
import io.temporal.serviceclient.GrpcFailure;
import io.temporal.serviceclient.GrpcStatusUtils;
import io.temporal.serviceclient.GrpcWorkflowServiceFactory;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class GenericWorkflowClientExternalImpl implements GenericWorkflowClientExternal {

  private final String domain;
  private final GrpcWorkflowServiceFactory service;
  private final Scope metricsScope;

  public GenericWorkflowClientExternalImpl(
      GrpcWorkflowServiceFactory service, String domain, Scope metricsScope) {
    this.service = service;
    this.domain = domain;
    this.metricsScope = metricsScope;
  }

  @Override
  public String getDomain() {
    return domain;
  }

  @Override
  public GrpcWorkflowServiceFactory getService() {
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
              .put(MetricsTag.DOMAIN, domain)
              .build();
      metricsScope.tagged(tags).counter(MetricsType.WORKFLOW_START_COUNTER).inc(1);
    }
  }

  private WorkflowExecution startWorkflowInternal(
      StartWorkflowExecutionParameters startParameters) {
    // TODO: (vkoby) refactor
    StartWorkflowExecutionRequest.Builder requestBuilder =
        StartWorkflowExecutionRequest.newBuilder();
    if (startParameters.getInput() != null) {
      requestBuilder.setInput(ByteString.copyFrom(startParameters.getInput()));
    }
    String taskList = startParameters.getTaskList();
    if (taskList != null && !taskList.isEmpty()) {
      TaskList tl = TaskList.newBuilder().setName(taskList).build();
      requestBuilder.setTaskList(tl);
    }
    String workflowId = startParameters.getWorkflowId();
    if (workflowId == null) {
      workflowId = UUID.randomUUID().toString();
    }
    RetryParameters retryParameters = startParameters.getRetryParameters();
    if (retryParameters != null) {
      RetryPolicy retryPolicy = toRetryPolicy(retryParameters);
      requestBuilder.setRetryPolicy(retryPolicy);
    }
    if (!Strings.isNullOrEmpty(startParameters.getCronSchedule())) {
      requestBuilder.setCronSchedule(startParameters.getCronSchedule());
    }
    StartWorkflowExecutionRequest request =
        requestBuilder
            .setDomain(domain)
            .setExecutionStartToCloseTimeoutSeconds(
                (int) startParameters.getExecutionStartToCloseTimeoutSeconds())
            .setTaskStartToCloseTimeoutSeconds(
                (int) startParameters.getTaskStartToCloseTimeoutSeconds())
            .setWorkflowIdReusePolicy(startParameters.getWorkflowIdReusePolicy())
            .setWorkflowId(workflowId)
            .setWorkflowType(startParameters.getWorkflowType())
            .setMemo(toMemoProto(startParameters.getMemo()))
            .setSearchAttributes(toSearchAttributesProto(startParameters.getSearchAttributes()))
            .build();

    StartWorkflowExecutionResponse result;
    try {
      result =
          Retryer.retryWithResult(
              Retryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
              () -> service.blockingStub().startWorkflowExecution(request));
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.ALREADY_EXISTS)
          && GrpcStatusUtils.hasFailure(
              e, GrpcFailure.WORKFLOW_EXECUTION_ALREADY_STARTED_FAILURE)) {
        throw e;
      } else {
        throw CheckedExceptionWrapper.wrap(e);
      }
    }
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setRunId(result.getRunId())
            .setWorkflowId(request.getWorkflowId())
            .build();

    return execution;
  }

  // TODO: (vkoby) Refactor
    private Memo toMemoProto(Map<String, byte[]> memo) {
      if (memo == null || memo.isEmpty()) {
        return null;
      }
      Map<String, ByteString> fields = new HashMap<>();
      for (Map.Entry<String, byte[]> item : memo.entrySet()) {
        fields.put(item.getKey(), ByteString.copyFrom(item.getValue()));
      }
      Memo memoProto = Memo.newBuilder().putAllFields(fields).build();
      return memoProto;
    }

    private SearchAttributes toSearchAttributesProto(Map<String, byte[]> searchAttributes) {
      if (searchAttributes == null || searchAttributes.isEmpty()) {
        return null;
      }
      Map<String, ByteString> fields = new HashMap<>();
      for (Map.Entry<String, byte[]> item : searchAttributes.entrySet()) {
        fields.put(item.getKey(), ByteString.copyFrom(item.getValue()));
      }
      SearchAttributes searchAttrThrift = SearchAttributes.newBuilder()
              .putAllIndexedFields(fields)
              .build();
      return searchAttrThrift;
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
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setRunId(signalParameters.getRunId())
            .setWorkflowId(signalParameters.getWorkflowId())
            .build();
    SignalWorkflowExecutionRequest request =
        SignalWorkflowExecutionRequest.newBuilder()
            .setDomain(domain)
            .setInput(ByteString.copyFrom(signalParameters.getInput()))
            .setSignalName(signalParameters.getSignalName())
            .setWorkflowExecution(execution)
            .build();
    try {
      Retryer.retry(
          Retryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
          () -> service.blockingStub().signalWorkflowExecution(request));
    } catch (RuntimeException e) {
      throw CheckedExceptionWrapper.wrap(e);
    }
  }

  @Override
  public WorkflowExecution signalWithStartWorkflowExecution(
      SignalWithStartWorkflowExecutionParameters parameters) {
    try {
      return signalWithStartWorkflowInternal(parameters);
    } finally {
      Map<String, String> tags =
          new ImmutableMap.Builder<String, String>(3)
              .put(
                  MetricsTag.WORKFLOW_TYPE,
                  parameters.getStartParameters().getWorkflowType().getName())
              .put(MetricsTag.TASK_LIST, parameters.getStartParameters().getTaskList())
              .put(MetricsTag.DOMAIN, domain)
              .build();
      metricsScope.tagged(tags).counter(MetricsType.WORKFLOW_SIGNAL_WITH_START_COUNTER).inc(1);
    }
  }

  private WorkflowExecution signalWithStartWorkflowInternal(
      SignalWithStartWorkflowExecutionParameters parameters) {
    StartWorkflowExecutionParameters startParameters = parameters.getStartParameters();
    SignalWithStartWorkflowExecutionRequest.Builder requestBuilder =
        SignalWithStartWorkflowExecutionRequest.newBuilder();
    // TODO: request.setIdentity()
    if (startParameters.getInput() != null) {
      requestBuilder.setInput(ByteString.copyFrom(startParameters.getInput()));
    }
    String taskList = startParameters.getTaskList();
    if (taskList != null && !taskList.isEmpty()) {
      TaskList tl = TaskList.newBuilder().setName(taskList).build();
      requestBuilder.setTaskList(tl);
    }
    String workflowId = startParameters.getWorkflowId();
    if (workflowId == null) {
      workflowId = UUID.randomUUID().toString();
    }
    RetryParameters retryParameters = startParameters.getRetryParameters();
    if (retryParameters != null) {
      RetryPolicy retryPolicy = toRetryPolicy(retryParameters);
      requestBuilder.setRetryPolicy(retryPolicy);
    }
    if (!Strings.isNullOrEmpty(startParameters.getCronSchedule())) {
      requestBuilder.setCronSchedule(startParameters.getCronSchedule());
    }
    SignalWithStartWorkflowExecutionRequest request =
        requestBuilder
            .setDomain(domain)
            .setSignalName(parameters.getSignalName())
            .setSignalInput(ByteString.copyFrom(parameters.getSignalInput()))
            .setExecutionStartToCloseTimeoutSeconds(
                (int) startParameters.getExecutionStartToCloseTimeoutSeconds())
            .setTaskStartToCloseTimeoutSeconds(
                (int) startParameters.getTaskStartToCloseTimeoutSeconds())
            .setWorkflowIdReusePolicy(startParameters.getWorkflowIdReusePolicy())
            .setWorkflowId(workflowId)
            .setWorkflowType(startParameters.getWorkflowType())
            .build();
    // TODO: (vkoby) Result was initially of type StartWorkflowExecutionResponse. Was this a bug?
    SignalWithStartWorkflowExecutionResponse result;
    try {
      result =
          Retryer.retryWithResult(
              Retryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
              () -> service.blockingStub().signalWithStartWorkflowExecution(request));
    } catch (StatusRuntimeException e) {
      throw CheckedExceptionWrapper.wrap(e);
    }
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setRunId(result.getRunId())
            .setWorkflowId(request.getWorkflowId())
            .build();
    return execution;
  }

  @Override
  public void requestCancelWorkflowExecution(WorkflowExecution execution) {
    RequestCancelWorkflowExecutionRequest request =
        RequestCancelWorkflowExecutionRequest.newBuilder()
            .setDomain(domain)
            .setWorkflowExecution(execution)
            .build();
    try {
      Retryer.retry(
          Retryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
          () -> service.blockingStub().requestCancelWorkflowExecution(request));
    } catch (StatusRuntimeException e) {
      throw CheckedExceptionWrapper.wrap(e);
    }
  }

  @Override
  public QueryWorkflowResponse queryWorkflow(QueryWorkflowParameters queryParameters) {
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(queryParameters.getWorkflowId())
            .setRunId(queryParameters.getRunId())
            .build();
    WorkflowQuery query =
        WorkflowQuery.newBuilder()
            .setQueryArgs(ByteString.copyFrom(queryParameters.getInput()))
            .setQueryType(queryParameters.getQueryType())
            .build();
    QueryWorkflowRequest request =
        QueryWorkflowRequest.newBuilder()
            .setDomain(domain)
            .setExecution(execution)
            .setQuery(query)
            .setQueryRejectCondition(queryParameters.getQueryRejectCondition())
            .build();
    try {
      QueryWorkflowResponse response =
          Retryer.retryWithResult(
              Retryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
              () -> service.blockingStub().queryWorkflow(request));
      return response;
    } catch (StatusRuntimeException e) {
      throw CheckedExceptionWrapper.wrap(e);
    }
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
            .setDomain(domain)
            .setWorkflowExecution(terminateParameters.getWorkflowExecution())
            .setDetails(ByteString.copyFrom(terminateParameters.getDetails()))
            .setReason(terminateParameters.getReason())
            .build();
    try {
      Retryer.retry(
          Retryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
          () -> service.blockingStub().terminateWorkflowExecution(request));
    } catch (StatusRuntimeException e) {
      throw CheckedExceptionWrapper.wrap(e);
    }
  }
}
