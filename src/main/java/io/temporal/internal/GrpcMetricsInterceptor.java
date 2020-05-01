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

package io.temporal.internal;

import static io.grpc.Status.Code.ALREADY_EXISTS;

import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.proto.workflowservice.WorkflowServiceGrpc;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Reports metrics on GRPC service calls */
class GrpcMetricsInterceptor implements ClientInterceptor {

  private final Map<MethodDescriptor<?, ?>, Scope> methodScopes = new HashMap<>();

  GrpcMetricsInterceptor(Scope scope) {
    ServiceDescriptor descriptor = WorkflowServiceGrpc.getServiceDescriptor();
    String serviceName = descriptor.getName();
    Collection<MethodDescriptor<?, ?>> methods = descriptor.getMethods();
    for (MethodDescriptor<?, ?> method : methods) {
      String name = method.getFullMethodName().substring(serviceName.length());
      String scopeName = MetricsType.TEMPORAL_METRICS_PREFIX + name;
      methodScopes.put(method, scope.subScope(scopeName));
    }
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    Scope scope = methodScopes.get(method);
    return new MetricsClientCall<>(next, method, callOptions, scope);
  }

  private static class MetricsClientCall<ReqT, RespT>
      extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {
    private final Scope scope;
    private final Stopwatch sw;

    public MetricsClientCall(
        Channel next, MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Scope scope) {
      super(next.newCall(method, callOptions));
      this.scope = scope;
      scope.counter(MetricsType.TEMPORAL_REQUEST).inc(1);
      sw = scope.timer(MetricsType.TEMPORAL_LATENCY).start();
    }

    @Override
    public void sendMessage(ReqT message) {
      super.sendMessage(message);
    }

    @Override
    public void start(Listener<RespT> responseListener, Metadata headers) {
      Listener<RespT> listener =
          new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
              responseListener) {
            @Override
            public void onClose(Status status, Metadata trailers) {
              if (!status.isOk()) {
                Status.Code code = status.getCode();
                if (code == Status.Code.INVALID_ARGUMENT || code == ALREADY_EXISTS) {
                  scope.counter(MetricsType.TEMPORAL_INVALID_REQUEST).inc(1);
                } else {
                  scope.counter(MetricsType.TEMPORAL_ERROR).inc(1);
                }
              }
              super.onClose(status, trailers);
              sw.stop();
            }
          };

      super.start(listener, headers);
    }
  }
}
