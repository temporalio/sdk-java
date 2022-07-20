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

package io.temporal.serviceclient;

import io.grpc.*;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

public class SystemInfoInterceptor implements ClientInterceptor {

  private final CompletableFuture<GetSystemInfoResponse.Capabilities> serverCapabilitiesFuture;

  public SystemInfoInterceptor(
      CompletableFuture<GetSystemInfoResponse.Capabilities> serverCapabilitiesFuture) {
    this.serverCapabilitiesFuture = serverCapabilitiesFuture;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
        next.newCall(method, callOptions)) {

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        if (!serverCapabilitiesFuture.isDone()) {
          if (method == WorkflowServiceGrpc.getGetSystemInfoMethod()) {
            // It's already a getSystemsInfo call, so we just listen on it and populate the
            // capabilities
            responseListener =
                new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
                    responseListener) {
                  @Override
                  public void onMessage(RespT message) {
                    if (message instanceof GetSystemInfoResponse) {
                      GetSystemInfoResponse response = (GetSystemInfoResponse) message;
                      serverCapabilitiesFuture.complete(response.getCapabilities());
                    }
                    super.onMessage(message);
                  }

                  @Override
                  public void onClose(Status status, Metadata trailers) {
                    if (Status.UNIMPLEMENTED.getCode().equals(status.getCode())) {
                      serverCapabilitiesFuture.complete(
                          GetSystemInfoResponse.Capabilities.getDefaultInstance());
                    }
                    super.onClose(status, trailers);
                  }
                };
          } else {
            // Need to reach system capabilities, so make a getSystemInfo call in a blocking manner.
            // We don't try to squash into one and optimize the several getSystemInfo calls that may
            // be initiated by several client calls here. Doing so it will require tricky
            // implementation to ensure proper deadlines that may be different between calls.
            // If a server is able to take the load of the requests, it should be able to serve some
            // additional lightweight static getSystemInfo calls that are serialized with the actual
            // calls.
            serverCapabilitiesFuture.complete(
                getServerCapabilitiesOrThrow(next, callOptions.getDeadline()));
          }
        }

        super.start(responseListener, headers);
      }
    };
  }

  public static GetSystemInfoResponse.Capabilities getServerCapabilitiesOrThrow(
      Channel channel, @Nullable Deadline deadline) {
    try {
      return WorkflowServiceGrpc.newBlockingStub(channel)
          .withDeadline(deadline)
          .getSystemInfo(GetSystemInfoRequest.newBuilder().build())
          .getCapabilities();
    } catch (StatusRuntimeException ex) {
      if (Status.Code.UNIMPLEMENTED.equals(ex.getStatus().getCode())) {
        return GetSystemInfoResponse.Capabilities.getDefaultInstance();
      }
      throw ex;
    }
  }
}
