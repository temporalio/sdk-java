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

import io.nexusrpc.OperationInfo;
import io.nexusrpc.OperationUnsuccessfulException;
import io.nexusrpc.handler.*;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkerInterceptor;

public class NexusServiceInterceptorConverter implements ServiceHandlerInterceptor {
  private final WorkerInterceptor[] interceptors;
  RootNexusOperationInboundCallsInterceptor rootInboundCallsInterceptor;

  public NexusServiceInterceptorConverter(WorkerInterceptor[] interceptors) {
    this.interceptors = interceptors;
  }

  @Override
  public OperationInterceptor interceptOperation(OperationInterceptor operationInterceptor) {
    rootInboundCallsInterceptor =
        new RootNexusOperationInboundCallsInterceptor(operationInterceptor);
    NexusOperationInboundCallsInterceptor inboundCallsInterceptor = rootInboundCallsInterceptor;
    for (WorkerInterceptor interceptor : interceptors) {
      inboundCallsInterceptor = interceptor.interceptNexusOperation(inboundCallsInterceptor);
    }

    inboundCallsInterceptor.init(
        new RootNexusOperationOutboundCallsInterceptor(
            CurrentNexusOperationContext.get().getMetricsScope()));
    return new OperationInterceptorConverter(inboundCallsInterceptor);
  }

  class OperationInterceptorConverter implements OperationInterceptor {
    private final NexusOperationInboundCallsInterceptor next;

    public OperationInterceptorConverter(NexusOperationInboundCallsInterceptor next) {
      this.next = next;
    }

    @Override
    public OperationStartResult<Object> start(
        OperationContext operationContext, OperationStartDetails operationStartDetails, Object o)
        throws OperationUnsuccessfulException {
      return next.startOperation(
              new NexusOperationInboundCallsInterceptor.StartOperationInput(
                  operationContext, operationStartDetails, o))
          .getResult();
    }

    @Override
    public Object fetchResult(
        OperationContext operationContext, OperationFetchResultDetails operationFetchResultDetails)
        throws OperationHandlerException {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public OperationInfo fetchInfo(
        OperationContext operationContext, OperationFetchInfoDetails operationFetchInfoDetails)
        throws OperationHandlerException {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void cancel(
        OperationContext operationContext, OperationCancelDetails operationCancelDetails) {
      next.cancelOperation(
          new NexusOperationInboundCallsInterceptor.CancelOperationInput(
              operationContext, operationCancelDetails));
    }
  }
}
