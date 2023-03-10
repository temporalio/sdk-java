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

package io.temporal.internal.client;

import static io.temporal.internal.common.HeaderUtils.intoPayloadMap;
import static io.temporal.internal.common.HeaderUtils.toHeaderGrpc;
import static io.temporal.internal.common.SerializerUtils.toRetryPolicy;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.*;
import io.temporal.api.enums.v1.HistoryEventFilterType;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequestOrBuilder;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class WorkflowClientRequestFactory {
  private final WorkflowClientOptions clientOptions;

  public WorkflowClientRequestFactory(WorkflowClientOptions clientOptions) {
    this.clientOptions = clientOptions;
  }

  // If you add anything new here, keep newSignalWithStartWorkflowExecutionRequest in sync
  @Nonnull
  StartWorkflowExecutionRequest.Builder newStartWorkflowExecutionRequest(
      @Nonnull String workflowId,
      @Nonnull String workflowTypeName,
      @Nonnull io.temporal.common.interceptors.Header header,
      @Nonnull WorkflowOptions options,
      @Nullable Payloads inputArgs) {

    StartWorkflowExecutionRequest.Builder request =
        StartWorkflowExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setRequestId(generateUniqueId())
            .setIdentity(clientOptions.getIdentity())
            .setWorkflowId(workflowId)
            .setWorkflowType(WorkflowType.newBuilder().setName(workflowTypeName))
            .setWorkflowRunTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getWorkflowRunTimeout()))
            .setWorkflowExecutionTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getWorkflowExecutionTimeout()))
            .setWorkflowTaskTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getWorkflowTaskTimeout()));

    if (inputArgs != null) {
      request.setInput(inputArgs);
    }

    if (options.getWorkflowIdReusePolicy() != null) {
      request.setWorkflowIdReusePolicy(options.getWorkflowIdReusePolicy());
    }

    String taskQueue = options.getTaskQueue();
    if (taskQueue != null && !taskQueue.isEmpty()) {
      request.setTaskQueue(TaskQueue.newBuilder().setName(taskQueue).build());
    }

    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      request.setRetryPolicy(toRetryPolicy(retryOptions));
    }

    if (!Strings.isNullOrEmpty(options.getCronSchedule())) {
      request.setCronSchedule(options.getCronSchedule());
    }

    if (options.getMemo() != null) {
      request.setMemo(
          Memo.newBuilder()
              .putAllFields(intoPayloadMap(clientOptions.getDataConverter(), options.getMemo())));
    }

    if (options.getSearchAttributes() != null && !options.getSearchAttributes().isEmpty()) {
      request.setSearchAttributes(SearchAttributesUtil.encode(options.getSearchAttributes()));
    }

    Header grpcHeader =
        toHeaderGrpc(header, extractContextsAndConvertToBytes(options.getContextPropagators()));
    request.setHeader(grpcHeader);

    return request;
  }

  @Nonnull
  SignalWithStartWorkflowExecutionRequest.Builder newSignalWithStartWorkflowExecutionRequest(
      @Nonnull StartWorkflowExecutionRequestOrBuilder startParameters,
      @Nonnull String signalName,
      @Nullable Payloads signalInput) {
    SignalWithStartWorkflowExecutionRequest.Builder request =
        SignalWithStartWorkflowExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setRequestId(generateUniqueId())
            .setIdentity(clientOptions.getIdentity())
            .setSignalName(signalName)
            .setWorkflowRunTimeout(startParameters.getWorkflowRunTimeout())
            .setWorkflowExecutionTimeout(startParameters.getWorkflowExecutionTimeout())
            .setWorkflowTaskTimeout(startParameters.getWorkflowTaskTimeout())
            .setWorkflowType(startParameters.getWorkflowType())
            .setWorkflowIdReusePolicy(startParameters.getWorkflowIdReusePolicy())
            .setCronSchedule(startParameters.getCronSchedule());

    String workflowId = startParameters.getWorkflowId();
    if (workflowId.isEmpty()) {
      workflowId = generateUniqueId();
    }
    request.setWorkflowId(workflowId);

    if (signalInput != null) {
      request.setSignalInput(signalInput);
    }

    if (startParameters.hasInput()) {
      request.setInput(startParameters.getInput());
    }

    if (startParameters.hasTaskQueue()) {
      request.setTaskQueue(startParameters.getTaskQueue());
    }

    if (startParameters.hasRetryPolicy()) {
      request.setRetryPolicy(startParameters.getRetryPolicy());
    }

    if (startParameters.hasMemo()) {
      request.setMemo(startParameters.getMemo());
    }

    if (startParameters.hasSearchAttributes()) {
      request.setSearchAttributes(startParameters.getSearchAttributes());
    }

    if (startParameters.hasHeader()) {
      request.setHeader(startParameters.getHeader());
    }

    return request;
  }

  @Nonnull
  GetWorkflowExecutionHistoryRequest newHistoryLongPollRequest(
      WorkflowExecution workflowExecution, ByteString pageToken) {
    return GetWorkflowExecutionHistoryRequest.newBuilder()
        .setNamespace(clientOptions.getNamespace())
        .setExecution(workflowExecution)
        .setHistoryEventFilterType(HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT)
        .setWaitNewEvent(true)
        .setNextPageToken(pageToken)
        .build();
  }

  private io.temporal.common.interceptors.Header extractContextsAndConvertToBytes(
      List<ContextPropagator> workflowOptionsContextPropagators) {
    List<ContextPropagator> workflowClientContextPropagators =
        clientOptions.getContextPropagators();
    if ((workflowClientContextPropagators.isEmpty() && workflowOptionsContextPropagators == null)
        || (workflowOptionsContextPropagators != null
            && workflowOptionsContextPropagators.isEmpty())) {
      return null;
    }

    List<ContextPropagator> listToUse =
        MoreObjects.firstNonNull(
            workflowOptionsContextPropagators, workflowClientContextPropagators);
    Map<String, Payload> result = new HashMap<>();
    for (ContextPropagator propagator : listToUse) {
      result.putAll(propagator.serializeContext(propagator.getCurrentContext()));
    }
    return new io.temporal.common.interceptors.Header(result);
  }

  private static String generateUniqueId() {
    return UUID.randomUUID().toString();
  }
}
