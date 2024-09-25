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

package io.temporal.common.interceptors;

import io.nexusrpc.OperationUnsuccessfulException;
import io.nexusrpc.handler.*;
import io.temporal.common.Experimental;

/**
 * Intercepts inbound calls to the Nexus operation on the worker side.
 *
 * <p>An instance should be created in {@link
 * WorkerInterceptor#interceptNexus(NexusInboundCallsInterceptor)}.
 *
 * <p>Prefer extending {@link NexusInboundCallsInterceptorBase} and overriding only the methods you
 * need instead of implementing this interface directly. {@link NexusInboundCallsInterceptorBase}
 * provides correct default implementations to all the methods of this interface.
 */
@Experimental
public interface NexusInboundCallsInterceptor {
  final class StartOperationInput {
    private final OperationContext operationContext;
    private final OperationStartDetails startDetails;
    private final HandlerInputContent input;

    public StartOperationInput(
        OperationContext operationContext,
        OperationStartDetails startDetails,
        HandlerInputContent input) {
      this.operationContext = operationContext;
      this.startDetails = startDetails;
      this.input = input;
    }

    public OperationContext getOperationContext() {
      return operationContext;
    }

    public OperationStartDetails getStartDetails() {
      return startDetails;
    }

    public HandlerInputContent getInput() {
      return input;
    }
  }

  final class StartOperationOutput {
    private final OperationStartResult<HandlerResultContent> result;

    public StartOperationOutput(OperationStartResult<HandlerResultContent> result) {
      this.result = result;
    }

    public OperationStartResult<HandlerResultContent> getResult() {
      return result;
    }
  }

  final class CancelOperationInput {
    private final OperationContext operationContext;
    private final OperationCancelDetails cancelDetails;

    public CancelOperationInput(
        OperationContext operationContext, OperationCancelDetails cancelDetails) {
      this.operationContext = operationContext;
      this.cancelDetails = cancelDetails;
    }

    public OperationContext getOperationContext() {
      return operationContext;
    }

    public OperationCancelDetails getCancelDetails() {
      return cancelDetails;
    }
  }

  final class CancelOperationOutput {}

  void init(NexusOutboundCallsInterceptor outboundCalls);

  /**
   * Intercepts a call to start a Nexus operation.
   *
   * @param input input to the operation start.
   * @return result of the operation start.
   * @throws OperationUnsuccessfulException if the operation start failed.
   */
  StartOperationOutput startOperation(StartOperationInput input)
      throws OperationUnsuccessfulException;

  /**
   * Intercepts a call to cancel a Nexus operation.
   *
   * @param input input to the operation cancel.
   * @return result of the operation cancel.
   */
  CancelOperationOutput cancelOperation(CancelOperationInput input);
}
