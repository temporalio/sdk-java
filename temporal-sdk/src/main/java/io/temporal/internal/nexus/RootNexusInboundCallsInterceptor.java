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

package io.temporal.internal.nexus;

import io.nexusrpc.OperationUnsuccessfulException;
import io.nexusrpc.handler.HandlerResultContent;
import io.nexusrpc.handler.OperationStartResult;
import io.nexusrpc.handler.ServiceHandler;
import io.temporal.client.WorkflowClient;
import io.temporal.common.interceptors.NexusInboundCallsInterceptor;
import io.temporal.common.interceptors.NexusOutboundCallsInterceptor;

public class RootNexusInboundCallsInterceptor implements NexusInboundCallsInterceptor {
  private final ServiceHandler serviceHandler;
  private NexusOutboundCallsInterceptor outboundCalls;
  private final String taskQueue;
  private final WorkflowClient client;

  RootNexusInboundCallsInterceptor(
      ServiceHandler serviceHandler, String taskQueue, WorkflowClient client) {
    this.serviceHandler = serviceHandler;
    this.taskQueue = taskQueue;
    this.client = client;
  }

  @Override
  public void init(NexusOutboundCallsInterceptor outboundCalls) {
    this.outboundCalls = outboundCalls;
  }

  @Override
  public StartOperationOutput startOperation(StartOperationInput input)
      throws OperationUnsuccessfulException {
    CurrentNexusOperationContext.set(
        new NexusOperationContextImpl(outboundCalls, taskQueue, client));
    try {
      OperationStartResult<HandlerResultContent> result =
          serviceHandler.startOperation(
              input.getOperationContext(), input.getStartDetails(), input.getInput());
      return new StartOperationOutput(result);
    } finally {
      CurrentNexusOperationContext.unset();
    }
  }

  @Override
  public CancelOperationOutput cancelOperation(CancelOperationInput input) {
    CurrentNexusOperationContext.set(
        new NexusOperationContextImpl(outboundCalls, taskQueue, client));
    try {
      serviceHandler.cancelOperation(input.getOperationContext(), input.getCancelDetails());
      return new CancelOperationOutput();
    } finally {
      CurrentNexusOperationContext.unset();
    }
  }
}
