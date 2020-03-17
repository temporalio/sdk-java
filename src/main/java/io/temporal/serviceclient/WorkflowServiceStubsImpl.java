/*
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
import io.temporal.internal.Version;
import io.temporal.proto.workflowservice.WorkflowServiceGrpc;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** TODO: (vkoby) Add metrics. */
final class WorkflowServiceStubsImpl implements WorkflowServiceStubs {

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

  public static final String TEMPORAL_SERVICE_ADDRESS_ENV = "TEMPORAL_ADDRESS";

  protected WorkflowServiceStubsOptions options;
  protected ManagedChannel channel;
  protected WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
  protected WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub;

  /**
   * Creates a factory that connects to the Temporal according to the specified options.
   *
   * @param options connection options
   */
  WorkflowServiceStubsImpl(WorkflowServiceStubsOptions options) {
    init(options);
  }

  /**
   * Generates the client for an in-process service using an in-memory channel. Useful for testing,
   * usually with mock and spy services.
   */
  WorkflowServiceStubsImpl(WorkflowServiceGrpc.WorkflowServiceImplBase serviceImpl) {
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
    init(
        WorkflowServiceStubsOptions.newBuilder()
            .setChannel(InProcessChannelBuilder.forName(serverName).directExecutor().build())
            .build());
  }

  private void init(WorkflowServiceStubsOptions options) {
    options = WorkflowServiceStubsOptions.newBuilder(options).validateAndBuildWithDefaults();
    this.options = options;
    if (options.getChannel() != null) {
      this.channel = options.getChannel();
    } else {
      this.channel =
          ManagedChannelBuilder.forTarget(options.getTarget())
              .defaultLoadBalancingPolicy("round_robin")
              .usePlaintext()
              .build();
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
      interceptedChannel = ClientInterceptors.intercept(channel, tracingInterceptor);
    }
    blockingStub = WorkflowServiceGrpc.newBlockingStub(interceptedChannel);
    if (options.getBlockingStubInterceptor().isPresent()) {
      blockingStub = options.getBlockingStubInterceptor().get().apply(blockingStub);
    }
    futureStub = WorkflowServiceGrpc.newFutureStub(interceptedChannel);
    if (options.getFutureStubInterceptor().isPresent()) {
      futureStub = options.getFutureStubInterceptor().get().apply(futureStub);
    }
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
                    log.trace(
                        "Returned " + method.getFullMethodName() + " with output: " + message);
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

  /** Simple port validation */
  private static int validatePort(int port) {
    if (port < 0) {
      throw new IllegalArgumentException("0 or negative port");
    }
    return port;
  }

  @Override
  public void shutdown() {
    channel.shutdown();
  }

  @Override
  public void shutdownNow() {
    channel.shutdownNow();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return channel.awaitTermination(timeout, unit);
  }

  @Override
  public boolean isShutdown() {
    return channel.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return channel.isTerminated();
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
      String name = method.getFullMethodName();
      if (name.equals("workflowservice.WorkflowService/GetWorkflowExecutionHistory")) {
        if (deadline == null) {
          duration = options.getRpcLongPollTimeoutMillis();
        } else {
          duration = deadline.timeRemaining(TimeUnit.MILLISECONDS);
          if (duration > options.getRpcLongPollTimeoutMillis()) {
            duration = options.getRpcLongPollTimeoutMillis();
          }
        }
      } else if (name.equals("workflowservice.WorkflowService/PollForDecisionTask")
          || name.equals("workflowservice.WorkflowService/PollForActivityTask")) {
        duration = options.getRpcLongPollTimeoutMillis();
      } else if (name.equals("workflowservice.WorkflowService/QueryWorkflow")) {
        duration = options.getRpcQueryTimeoutMillis();
      }
      if (log.isTraceEnabled()) {
        log.trace("TimeoutInterceptor method=" + name + ", timeoutMs=" + duration);
      }
      return next.newCall(method, callOptions.withDeadlineAfter(duration, TimeUnit.MILLISECONDS));
    }
  }
}
