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

package io.temporal.serviceclient;

import static io.temporal.serviceclient.MetricsTag.OPERATION_NAME;
import static io.temporal.serviceclient.MetricsTag.STATUS_CODE;

import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
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
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import java.util.*;

/** Reports metrics on GRPC service calls */
class GrpcMetricsInterceptor implements ClientInterceptor {
  private static final Map<Status.Code, Map<String, String>> STATUS_CODE_TAGS;

  private final Scope defaultScope;
  private final Map<MethodDescriptor<?, ?>, Map<String, String>> methodTags;

  GrpcMetricsInterceptor(Scope scope) {
    this.defaultScope = scope.tagged(MetricsTag.defaultTags(MetricsTag.DEFAULT_VALUE));
    ServiceDescriptor descriptor = WorkflowServiceGrpc.getServiceDescriptor();
    String serviceName = descriptor.getName();

    Map<MethodDescriptor<?, ?>, Map<String, String>> methodTags = new HashMap<>();
    Collection<MethodDescriptor<?, ?>> methods = descriptor.getMethods();
    for (MethodDescriptor<?, ?> method : methods) {
      int beginIndex = serviceName.length() + 1;
      String name = method.getFullMethodName().substring(beginIndex);
      Map<String, String> tags =
          new ImmutableMap.Builder<String, String>(1).put(OPERATION_NAME, name).build();
      methodTags.put(method, tags);
    }
    this.methodTags = Collections.unmodifiableMap(methodTags);
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    Scope scope = callOptions.getOption(MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY);
    if (scope == null) {
      scope = defaultScope;
    }
    Map<String, String> tags = methodTags.get(method);
    scope = scope.tagged(tags);
    return new MetricsClientCall<>(next, method, callOptions, scope);
  }

  private static class MetricsClientCall<ReqT, RespT>
      extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {
    private final Scope metricsScope;
    private final Stopwatch sw;
    private final boolean longPoll;

    MetricsClientCall(
        Channel next,
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Scope metricsScope) {
      super(next.newCall(method, callOptions));
      this.metricsScope = metricsScope;
      longPoll = LongPollUtil.isLongPoll(method, callOptions);
      if (longPoll) {
        metricsScope.counter(MetricsType.TEMPORAL_LONG_REQUEST).inc(1);
        sw = metricsScope.timer(MetricsType.TEMPORAL_LONG_REQUEST_LATENCY).start();
      } else {
        metricsScope.counter(MetricsType.TEMPORAL_REQUEST).inc(1);
        sw = metricsScope.timer(MetricsType.TEMPORAL_REQUEST_LATENCY).start();
      }
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
              try {
                // This method SHOULDN'T throw according to it's javadoc,
                // but we allow users setting custom interceptors.
                // So we protect from an incorrectly implemented custom user gRPC interceptor
                // by putting temporal metrics code into finally block
                super.onClose(status, trailers);
              } finally {
                sw.stop();
                if (!status.isOk()) {
                  Scope scope = metricsScope.tagged(STATUS_CODE_TAGS.get(status.getCode()));
                  if (longPoll) {
                    scope.counter(MetricsType.TEMPORAL_LONG_REQUEST_FAILURE).inc(1);
                  } else {
                    scope.counter(MetricsType.TEMPORAL_REQUEST_FAILURE).inc(1);
                  }
                }
              }
            }
          };

      super.start(listener, headers);
    }
  }

  static {
    Map<Status.Code, Map<String, String>> codeTags = new EnumMap<>(Status.Code.class);
    Arrays.stream(Status.Code.values())
        .forEach(
            code ->
                codeTags.put(
                    code,
                    new ImmutableMap.Builder<String, String>(1)
                        .put(STATUS_CODE, code.name())
                        .build()));
    STATUS_CODE_TAGS = Collections.unmodifiableMap(codeTags);
  }
}
