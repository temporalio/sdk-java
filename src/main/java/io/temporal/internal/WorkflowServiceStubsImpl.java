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

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.temporal.proto.workflowservice.WorkflowServiceGrpc;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** TODO: (vkoby) Add metrics. */
public final class WorkflowServiceStubsImpl implements WorkflowServiceStubs {

  private static final Logger log = LoggerFactory.getLogger(WorkflowServiceStubsImpl.class);

  /** refers to the name of the gRPC header that contains the client library version */
  private static final Metadata.Key<String> LIBRARY_VERSION_HEADER_KEY =
      Metadata.Key.of("temporal-sdk-version", Metadata.ASCII_STRING_MARSHALLER);

  /** refers to the name of the gRPC header that contains the client feature version */
  private static final Metadata.Key<String> FEATURE_VERSION_HEADER_KEY =
      Metadata.Key.of("temporal-sdk-feature-version", Metadata.ASCII_STRING_MARSHALLER);

  /** refers to the name of the gRPC header that contains the client SDK name */
  private static final Metadata.Key<String> CLIENT_IMPL_HEADER_KEY =
      Metadata.Key.of("temporal-sdk-name", Metadata.ASCII_STRING_MARSHALLER);

  private static final String CLIENT_IMPL_HEADER_VALUE = "temporal-java";

  private final ManagedChannel channel;
  // Shutdown channel that was created by us
  private final boolean channelNeedsShutdown;
  private final AtomicBoolean shutdownRequested = new AtomicBoolean();
  private final WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
  private final WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub;

  /**
   * Creates a factory that connects to the Temporal according to the specified options. When
   * serviceImpl is not null generates the client for an in-process service using an in-memory
   * channel. Useful for testing, usually with mock and spy services.
   */
  public WorkflowServiceStubsImpl(
      WorkflowServiceGrpc.WorkflowServiceImplBase serviceImpl,
      WorkflowServiceStubsOptions options) {
    if (serviceImpl != null) {
      if (options.getChannel() != null) {
        throw new IllegalArgumentException("both channel and serviceImpl present");
      }
      String serverName = InProcessServerBuilder.generateName();
      try {
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(serviceImpl)
            .build()
            .start();
      } catch (IOException unexpected) {
        throw new RuntimeException(unexpected);
      }
      options =
          WorkflowServiceStubsOptions.newBuilder(options)
              .setChannel(InProcessChannelBuilder.forName(serverName).directExecutor().build())
              .build();
    }
    options = WorkflowServiceStubsOptions.newBuilder(options).validateAndBuildWithDefaults();
    if (options.getChannel() != null) {
      this.channel = options.getChannel();
      // Do not shutdown a channel passed to the constructor from outside
      channelNeedsShutdown = serviceImpl != null;
    } else {
      this.channel =
          ManagedChannelBuilder.forTarget(options.getTarget())
              .defaultLoadBalancingPolicy("round_robin")
              .usePlaintext()
              .build();
      channelNeedsShutdown = true;
    }
    ClientInterceptor deadlineInterceptor = new GrpcDeadlineInterceptor(options);
    ClientInterceptor tracingInterceptor = newTracingInterceptor();
    Metadata headers = new Metadata();
    headers.put(LIBRARY_VERSION_HEADER_KEY, Version.LIBRARY_VERSION);
    headers.put(FEATURE_VERSION_HEADER_KEY, Version.FEATURE_VERSION);
    headers.put(CLIENT_IMPL_HEADER_KEY, CLIENT_IMPL_HEADER_VALUE);
    Channel interceptedChannel =
        ClientInterceptors.intercept(
            channel, deadlineInterceptor, MetadataUtils.newAttachHeadersInterceptor(headers));
    if (log.isTraceEnabled()) {
      interceptedChannel = ClientInterceptors.intercept(interceptedChannel, tracingInterceptor);
    }
    WorkflowServiceGrpc.WorkflowServiceBlockingStub bs =
        WorkflowServiceGrpc.newBlockingStub(interceptedChannel);
    if (options.getBlockingStubInterceptor().isPresent()) {
      bs = options.getBlockingStubInterceptor().get().apply(bs);
    }
    this.blockingStub = bs;
    WorkflowServiceGrpc.WorkflowServiceFutureStub fs =
        WorkflowServiceGrpc.newFutureStub(interceptedChannel);
    if (options.getFutureStubInterceptor().isPresent()) {
      fs = options.getFutureStubInterceptor().get().apply(fs);
    }
    this.futureStub = fs;
    log.info(String.format("Created GRPC client for channel: %s", channel));
  }

  private ClientInterceptor newTracingInterceptor() {
    return new ClientInterceptor() {

      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions)) {
          @Override
          public void sendMessage(ReqT message) {
            log.trace("Invoking " + method.getFullMethodName() + "with input: " + message);
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
                    if (method == WorkflowServiceGrpc.getPollForDecisionTaskMethod()) {
                      log.trace("Returned " + method.getFullMethodName());
                    } else {
                      log.trace(
                          "Returned " + method.getFullMethodName() + " with output: " + message);
                    }
                    super.onMessage(message);
                  }
                };
            super.start(listener, headers);
          }
        };
      }
    };
  }

  /** @return Blocking (synchronous) stub that allows direct calls to service. */
  public WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub() {
    return blockingStub;
  }

  /** @return Future (asynchronous) stub that allows direct calls to service. */
  public WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub() {
    return futureStub;
  }

  @Override
  public void shutdown() {
    shutdownRequested.set(true);
    if (channelNeedsShutdown) {
      channel.shutdown();
    }
  }

  @Override
  public void shutdownNow() {
    shutdownRequested.set(true);
    if (channelNeedsShutdown) {
      channel.shutdownNow();
    }
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    if (channelNeedsShutdown) {
      return channel.awaitTermination(timeout, unit);
    }
    return true;
  }

  @Override
  public boolean isShutdown() {
    if (channelNeedsShutdown) {
      return channel.isShutdown();
    }
    return shutdownRequested.get();
  }

  @Override
  public boolean isTerminated() {
    if (channelNeedsShutdown) {
      return channel.isTerminated();
    }
    return shutdownRequested.get();
  }

  /** Set RPC call deadlines according to ServiceFactoryOptions. */
  private static class GrpcDeadlineInterceptor implements ClientInterceptor {

    private final WorkflowServiceStubsOptions options;

    public GrpcDeadlineInterceptor(WorkflowServiceStubsOptions options) {
      this.options = options;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      Deadline deadline = callOptions.getDeadline();
      long duration;
      if (deadline == null) {
        duration = options.getRpcTimeoutMillis();
      } else {
        duration = deadline.timeRemaining(TimeUnit.MILLISECONDS);
      }
      if (method == WorkflowServiceGrpc.getGetWorkflowExecutionHistoryMethod()) {
        if (deadline == null) {
          duration = options.getRpcLongPollTimeoutMillis();
        } else {
          duration = deadline.timeRemaining(TimeUnit.MILLISECONDS);
          if (duration > options.getRpcLongPollTimeoutMillis()) {
            duration = options.getRpcLongPollTimeoutMillis();
          }
        }
      } else if (method == WorkflowServiceGrpc.getPollForDecisionTaskMethod()
          || method == WorkflowServiceGrpc.getPollForActivityTaskMethod()) {
        duration = options.getRpcLongPollTimeoutMillis();
      } else if (method == WorkflowServiceGrpc.getQueryWorkflowMethod()) {
        duration = options.getRpcQueryTimeoutMillis();
      }
      if (log.isTraceEnabled()) {
        String name = method.getFullMethodName();
        log.trace("TimeoutInterceptor method=" + name + ", timeoutMs=" + duration);
      }
      return next.newCall(method, callOptions.withDeadlineAfter(duration, TimeUnit.MILLISECONDS));
    }
  }
}
