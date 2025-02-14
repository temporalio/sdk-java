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

package io.temporal.nexus;

import static io.temporal.internal.common.LinkConverter.workflowEventToNexusLink;
import static io.temporal.internal.common.NexusUtil.nexusProtoLinkToLink;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.nexusrpc.OperationInfo;
import io.nexusrpc.handler.*;
import io.nexusrpc.handler.OperationHandler;
import io.temporal.api.common.v1.Link;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.internal.client.NexusStartWorkflowRequest;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.internal.nexus.OperationToken;
import io.temporal.internal.nexus.WorkflowRunOperationToken;
import java.net.URISyntaxException;

class WorkflowRunOperationImpl<T, R> implements OperationHandler<T, R> {
  private final WorkflowHandleFactory<T, R> handleFactory;

  WorkflowRunOperationImpl(WorkflowHandleFactory<T, R> handleFactory) {
    this.handleFactory = handleFactory;
  }

  @Override
  public OperationStartResult<R> start(
      OperationContext ctx, OperationStartDetails operationStartDetails, T input) {
    InternalNexusOperationContext nexusCtx = CurrentNexusOperationContext.get();

    WorkflowHandle handle = handleFactory.apply(ctx, operationStartDetails, input);

    NexusStartWorkflowRequest nexusRequest =
        new NexusStartWorkflowRequest(
            operationStartDetails.getRequestId(),
            operationStartDetails.getCallbackUrl(),
            operationStartDetails.getCallbackHeaders(),
            nexusCtx.getTaskQueue(),
            operationStartDetails.getLinks());

    WorkflowExecution workflowExec = handle.getInvoker().invoke(nexusRequest);

    // Create the link information about the new workflow and return to the caller.
    Link.WorkflowEvent workflowEventLink =
        Link.WorkflowEvent.newBuilder()
            .setNamespace(nexusCtx.getNamespace())
            .setWorkflowId(workflowExec.getWorkflowId())
            .setRunId(workflowExec.getRunId())
            .setEventRef(
                Link.WorkflowEvent.EventReference.newBuilder()
                    .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED))
            .build();
    io.temporal.api.nexus.v1.Link nexusLink = workflowEventToNexusLink(workflowEventLink);
    // Generate the operation token for the new workflow.
    String operationToken;
    try {
      operationToken =
          OperationToken.generateWorkflowRunOperationToken(
              workflowExec.getWorkflowId(), nexusCtx.getNamespace());
    } catch (JsonProcessingException e) {
      // Not expected as the link is constructed by the SDK.
      throw new HandlerException(
          HandlerException.ErrorType.INTERNAL,
          new IllegalArgumentException("failed to generate workflow operation token", e));
    }
    // Attach the link to the operation result.
    OperationStartResult.Builder<R> result = OperationStartResult.newAsyncBuilder(operationToken);
    if (nexusLink != null) {
      try {
        ctx.addLinks(nexusProtoLinkToLink(nexusLink));
      } catch (URISyntaxException e) {
        // Not expected as the link is constructed by the SDK.
        throw new HandlerException(
            HandlerException.ErrorType.INTERNAL,
            new IllegalArgumentException("failed to parse URI", e));
      }
    }
    return result.build();
  }

  @Override
  public R fetchResult(
      OperationContext operationContext, OperationFetchResultDetails operationFetchResultDetails) {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public OperationInfo fetchInfo(
      OperationContext operationContext, OperationFetchInfoDetails operationFetchInfoDetails) {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public void cancel(
      OperationContext operationContext, OperationCancelDetails operationCancelDetails) {
    String workflowId;
    try {
      WorkflowRunOperationToken workflowRunToken =
          OperationToken.loadWorkflowRunOperationToken(operationCancelDetails.getOperationToken());
      workflowId = workflowRunToken.getWorkflowId();
    } catch (OperationToken.FallbackToWorkflowIdException e) {
      workflowId = operationCancelDetails.getOperationToken();
    } catch (IllegalArgumentException e) {
      throw new HandlerException(
          HandlerException.ErrorType.INTERNAL,
          new IllegalArgumentException("failed to parse operation token", e));
    }

    WorkflowClient client = CurrentNexusOperationContext.get().getWorkflowClient();
    client.newUntypedWorkflowStub(workflowId).cancel();
  }
}
