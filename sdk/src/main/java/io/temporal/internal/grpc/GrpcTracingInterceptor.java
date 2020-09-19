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

package io.temporal.internal.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GrpcTracingInterceptor implements ClientInterceptor {

  private static final Logger log = LoggerFactory.getLogger(GrpcTracingInterceptor.class);

  /**
   * Separate logger for PollWorkflowTaskQueue reply which includes history. It is separate to allow
   * disabling this noisy log independently through configuration.
   */
  private static final Logger workflow_task_log =
      LoggerFactory.getLogger(GrpcTracingInterceptor.class.getName() + ":history");

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
        next.newCall(method, callOptions)) {
      @Override
      public void sendMessage(ReqT message) {
        log.trace("Invoking \"" + method.getFullMethodName() + "\" with input: " + message);
        super.sendMessage(message);
      }

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        Listener<RespT> listener =
            new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
                responseListener) {
              @Override
              public void onMessage(RespT message) {
                // Skip printing the whole history
                if (method == WorkflowServiceGrpc.getPollWorkflowTaskQueueMethod()) {
                  if (workflow_task_log.isTraceEnabled()) {
                    workflow_task_log.trace(
                        "Returned \""
                            + method.getServiceName()
                            + "\" of \""
                            + method.getFullMethodName()
                            + "\" with output: "
                            + message);
                  } else if (log.isTraceEnabled()) {
                    log.trace("Returned " + method.getFullMethodName());
                  }
                } else if (log.isTraceEnabled()) {
                  log.trace(
                      "Returned \""
                          + method.getServiceName()
                          + "\" of \""
                          + method.getFullMethodName()
                          + "\" with output: "
                          + message);
                }
                super.onMessage(message);
              }
            };
        super.start(listener, headers);
      }
    };
  }

  public boolean isEnabled() {
    return log.isTraceEnabled() || workflow_task_log.isTraceEnabled();
  }
}
