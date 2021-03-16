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

package io.temporal.internal.sync;

import static io.temporal.internal.common.HeaderUtils.convertMapFromObjectToBytes;
import static io.temporal.internal.common.HeaderUtils.toHeaderGrpc;
import static io.temporal.internal.sync.SyncWorkflowContext.toRetryPolicy;

import com.google.common.base.Strings;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.*;
import io.temporal.api.errordetails.v1.WorkflowExecutionAlreadyStartedFailure;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.SignalWithStartWorkflowExecutionParameters;
import io.temporal.internal.common.StatusUtils;
import io.temporal.internal.external.GenericWorkflowClientExternal;
import java.util.*;

class RootWorkflowClientInvoker implements WorkflowClientCallsInterceptor {
  private final GenericWorkflowClientExternal genericClient;
  private final WorkflowClientOptions clientOptions;

  public RootWorkflowClientInvoker(
      GenericWorkflowClientExternal genericClient, WorkflowClientOptions clientOptions) {
    this.genericClient = genericClient;
    this.clientOptions = clientOptions;
  }

  @Override
  public WorkflowStartOutput start(WorkflowStartInput input) {
    StartWorkflowExecutionRequest request = newStartWorkflowExecutionRequest(input);
    try {
      return new WorkflowStartOutput(genericClient.start(request));
    } catch (StatusRuntimeException e) {
      throw wrapExecutionAlreadyStarted(request.getWorkflowId(), input.getWorkflowType(), e);
    }
  }

  @Override
  public WorkflowStartOutput signalWithStart(WorkflowStartWithSignalInput input) {
    StartWorkflowExecutionRequest request =
        newStartWorkflowExecutionRequest(input.getWorkflowStartInput());
    Optional<Payloads> signalInput =
        clientOptions.getDataConverter().toPayloads(input.getSignalArguments());
    SignalWithStartWorkflowExecutionParameters p =
        new SignalWithStartWorkflowExecutionParameters(request, input.getSignalName(), signalInput);
    try {
      return new WorkflowStartOutput(genericClient.signalWithStart(p));
    } catch (StatusRuntimeException e) {
      throw wrapExecutionAlreadyStarted(
          request.getWorkflowId(), input.getWorkflowStartInput().getWorkflowType(), e);
    }
  }

  private RuntimeException wrapExecutionAlreadyStarted(
      String workflowId, String workflowType, StatusRuntimeException e) {
    WorkflowExecutionAlreadyStartedFailure f =
        StatusUtils.getFailure(e, WorkflowExecutionAlreadyStartedFailure.class);
    if (f != null) {
      WorkflowExecution exe =
          WorkflowExecution.newBuilder().setWorkflowId(workflowId).setRunId(f.getRunId()).build();
      return new WorkflowExecutionAlreadyStarted(exe, workflowType, e);
    } else {
      return e;
    }
  }

  private StartWorkflowExecutionRequest newStartWorkflowExecutionRequest(WorkflowStartInput input) {
    WorkflowOptions options = input.getOptions();

    StartWorkflowExecutionRequest.Builder request =
        StartWorkflowExecutionRequest.newBuilder()
            .setWorkflowType(WorkflowType.newBuilder().setName(input.getWorkflowType()))
            .setRequestId(UUID.randomUUID().toString())
            .setWorkflowRunTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getWorkflowRunTimeout()))
            .setWorkflowExecutionTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getWorkflowExecutionTimeout()))
            .setWorkflowTaskTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getWorkflowTaskTimeout()));

    if (clientOptions.getIdentity() != null) {
      request.setIdentity(clientOptions.getIdentity());
    }
    if (clientOptions.getNamespace() != null) {
      request.setNamespace(clientOptions.getNamespace());
    }
    if (options.getWorkflowId() == null) {
      request.setWorkflowId(UUID.randomUUID().toString());
    } else {
      request.setWorkflowId(options.getWorkflowId());
    }
    Optional<Payloads> inputArgs =
        clientOptions.getDataConverter().toPayloads(input.getArguments());
    if (inputArgs.isPresent()) {
      request.setInput(inputArgs.get());
    }
    if (options.getWorkflowIdReusePolicy() != null) {
      request.setWorkflowIdReusePolicy(options.getWorkflowIdReusePolicy());
    }
    String taskQueue = options.getTaskQueue();
    if (taskQueue != null && !taskQueue.isEmpty()) {
      request.setTaskQueue(TaskQueue.newBuilder().setName(taskQueue).build());
    }
    String workflowId = options.getWorkflowId();
    if (workflowId == null) {
      workflowId = UUID.randomUUID().toString();
    }
    request.setWorkflowId(workflowId);
    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      request.setRetryPolicy(toRetryPolicy(retryOptions));
    }
    if (!Strings.isNullOrEmpty(options.getCronSchedule())) {
      request.setCronSchedule(options.getCronSchedule());
    }
    if (options.getMemo() != null) {
      request.setMemo(Memo.newBuilder().putAllFields(convertFromObjectToBytes(options.getMemo())));
    }
    if (options.getSearchAttributes() != null) {
      request.setSearchAttributes(
          SearchAttributes.newBuilder()
              .putAllIndexedFields(convertFromObjectToBytes(options.getSearchAttributes())));
    }

    Header grpcHeader =
        toHeaderGrpc(
            input.getHeader(), extractContextsAndConvertToBytes(options.getContextPropagators()));
    request.setHeader(grpcHeader);

    return request.build();
  }

  private Map<String, Payload> convertFromObjectToBytes(Map<String, Object> map) {
    return convertMapFromObjectToBytes(map, clientOptions.getDataConverter());
  }

  private io.temporal.common.interceptors.Header extractContextsAndConvertToBytes(
      List<ContextPropagator> contextPropagators) {
    if (contextPropagators == null) {
      return null;
    }
    Map<String, Payload> result = new HashMap<>();
    for (ContextPropagator propagator : contextPropagators) {
      result.putAll(propagator.serializeContext(propagator.getCurrentContext()));
    }
    return new io.temporal.common.interceptors.Header(result);
  }
}
