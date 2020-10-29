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

import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** TODO: (vkoby) Add metrics. */
public final class WorkflowServiceStubsImpl implements WorkflowServiceStubs {

  private static final Logger log = LoggerFactory.getLogger(WorkflowServiceStubsImpl.class);

  private static final int MAX_INBOUND_MESSAGE_SIZE = 25_000_000;

  /** refers to the name of the gRPC header that contains the client library version */
  private static final Metadata.Key<String> LIBRARY_VERSION_HEADER_KEY =
      Metadata.Key.of("client-version", Metadata.ASCII_STRING_MARSHALLER);

  /** refers to the name of the gRPC header that contains supported server versions */
  private static final Metadata.Key<String> SUPPORTED_SERVER_VERSIONS_HEADER_KEY =
      Metadata.Key.of("supported-server-versions", Metadata.ASCII_STRING_MARSHALLER);

  /** refers to the name of the gRPC header that contains the client SDK name */
  private static final Metadata.Key<String> CLIENT_NAME_HEADER_KEY =
      Metadata.Key.of("client-name", Metadata.ASCII_STRING_MARSHALLER);

  private static final String CLIENT_NAME_HEADER_VALUE = "temporal-java";

  private final WorkflowServiceStubsOptions options;
  private final ManagedChannel channel;
  // Shutdown channel that was created by us
  private final boolean channelNeedsShutdown;
  private final AtomicBoolean shutdownRequested = new AtomicBoolean();
  private final WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
  private final WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub;
  private final Server inProcessServer;

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
        inProcessServer =
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
    } else {
      inProcessServer = null;
    }
    options = WorkflowServiceStubsOptions.newBuilder(options).validateAndBuildWithDefaults();
    this.options = options;
    if (options.getChannel() != null) {
      this.channel = options.getChannel();
      // Do not shutdown a channel passed to the constructor from outside
      channelNeedsShutdown = serviceImpl != null;
    } else {
      NettyChannelBuilder builder =
          NettyChannelBuilder.forTarget(options.getTarget())
              .defaultLoadBalancingPolicy("round_robin")
              .maxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);

      if (options.getSslContext() == null && !options.getEnableHttps()) {
        builder.usePlaintext();
      } else if (options.getSslContext() != null) {
        builder.sslContext(options.getSslContext());
      } else {
        builder.useTransportSecurity();
      }

      this.channel = builder.build();
      channelNeedsShutdown = true;
    }
    GrpcMetricsInterceptor metricsInterceptor =
        new GrpcMetricsInterceptor(options.getMetricsScope());
    ClientInterceptor deadlineInterceptor = new GrpcDeadlineInterceptor(options);
    GrpcTracingInterceptor tracingInterceptor = new GrpcTracingInterceptor();
    Metadata headers = headersFrom(options.getHeaders());
    headers.put(LIBRARY_VERSION_HEADER_KEY, Version.LIBRARY_VERSION);
    headers.put(SUPPORTED_SERVER_VERSIONS_HEADER_KEY, Version.SUPPORTED_SERVER_VERSIONS);
    headers.put(CLIENT_NAME_HEADER_KEY, CLIENT_NAME_HEADER_VALUE);
    Channel interceptedChannel =
        ClientInterceptors.intercept(
            channel,
            metricsInterceptor,
            deadlineInterceptor,
            MetadataUtils.newAttachHeadersInterceptor(headers));
    if (tracingInterceptor.isEnabled()) {
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

  private Metadata headersFrom(Map<String, String> headers) {
    Metadata metadata = new Metadata();
    for (Entry<String, String> header : headers.entrySet()) {
      metadata.put(
          Metadata.Key.of(header.getKey(), Metadata.ASCII_STRING_MARSHALLER), header.getValue());
    }
    return metadata;
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
    log.info("shutdown");
    shutdownRequested.set(true);
    if (channelNeedsShutdown) {
      channel.shutdown();
    }
    if (inProcessServer != null) {
      inProcessServer.shutdown();
    }
  }

  @Override
  public void shutdownNow() {
    log.info("shutdownNow");
    shutdownRequested.set(true);
    if (channelNeedsShutdown) {
      channel.shutdownNow();
    }
    if (inProcessServer != null) {
      inProcessServer.shutdownNow();
    }
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long start = System.currentTimeMillis();
    if (channelNeedsShutdown) {
      return channel.awaitTermination(timeout, unit);
    }
    long left = System.currentTimeMillis() - unit.toMillis(start);
    if (inProcessServer != null) {
      inProcessServer.awaitTermination(left, TimeUnit.MILLISECONDS);
    }
    return true;
  }

  @Override
  public WorkflowServiceStubsOptions getOptions() {
    return options;
  }

  @Override
  public boolean isShutdown() {
    boolean result;
    if (channelNeedsShutdown) {
      result = channel.isShutdown();
    } else {
      result = shutdownRequested.get();
    }
    if (inProcessServer != null) {
      result = result && inProcessServer.isShutdown();
    }
    return result;
  }

  @Override
  public boolean isTerminated() {
    boolean result;
    if (channelNeedsShutdown) {
      result = channel.isTerminated();
    } else {
      result = shutdownRequested.get();
    }
    if (inProcessServer != null) {
      result = result && inProcessServer.isTerminated();
    }
    return result;
  }
}
