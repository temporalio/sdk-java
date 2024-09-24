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

import io.nexusrpc.OperationInfo;
import io.nexusrpc.handler.*;
import io.nexusrpc.handler.OperationHandler;
import io.temporal.client.WorkflowClient;
import io.temporal.internal.client.NexusStartWorkflowRequest;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.NexusOperationContextImpl;

class RunWorkflowOperation<T, R> implements OperationHandler<T, R> {
  private final WorkflowHandleFactory<T, R> handleFactory;

  RunWorkflowOperation(WorkflowHandleFactory<T, R> handleFactory) {
    this.handleFactory = handleFactory;
  }

  @Override
  public OperationStartResult<R> start(
      OperationContext ctx, OperationStartDetails operationStartDetails, T input) {
    NexusOperationContextImpl nexusCtx = CurrentNexusOperationContext.get();

    WorkflowHandle handle =
        handleFactory.apply(ctx, operationStartDetails, nexusCtx.getWorkflowClient(), input);

    NexusStartWorkflowRequest nexusRequest =
        new NexusStartWorkflowRequest(
            operationStartDetails.getRequestId(),
            operationStartDetails.getCallbackUrl(),
            operationStartDetails.getCallbackHeaders(),
            nexusCtx.getTaskQueue());
    return OperationStartResult.async(handle.getInvoker().invoke(nexusRequest).getWorkflowId());
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
    WorkflowClient client = CurrentNexusOperationContext.get().getWorkflowClient();
    client.newUntypedWorkflowStub(operationCancelDetails.getOperationId()).cancel();
  }
}
