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

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.internal.retryer.GrpcRetryer;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final String HEALTH_CHECK_SERVICE_NAME =
      "temporal.api.workflowservice.v1.WorkflowService";

  private final WorkflowServiceStubsOptions options;
  private final ManagedChannel channel;
  // Shutdown channel that was created by us
  private final boolean channelNeedsShutdown;
  private final AtomicBoolean shutdownRequested = new AtomicBoolean();
  private final WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
  private final WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub;
  private final Server inProcessServer;
  private final ScheduledExecutorService grpcConnectionManager;

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
              // target might be set already, especially if options were built with defaults. Need
              // to set it to null since we don't allow both channel and target be set at the same
              // time.
              .setTarget(null)
              .build();
    } else {
      inProcessServer = null;
    }
    options = WorkflowServiceStubsOptions.newBuilder(options).validateAndBuildWithDefaults();
    this.options = options;
    this.grpcConnectionManager = grpcConnectionManager();
    if (options.getChannel() != null) {
      this.channel = options.getChannel();
      // Do not shutdown a channel passed to the constructor from outside
      channelNeedsShutdown = serviceImpl != null;
    } else {
      NettyChannelBuilder builder =
          NettyChannelBuilder.forTarget(options.getTarget())
              .defaultLoadBalancingPolicy("round_robin")
              .maxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
      if (options.getEnableKeepAlive()) {
        builder
            .keepAliveTime(options.getKeepAliveTime().toMillis(), TimeUnit.MILLISECONDS)
            .keepAliveTimeout(options.getKeepAliveTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .keepAliveWithoutCalls(options.getKeepAlivePermitWithoutStream());
      }

      if (options.getSslContext() == null && !options.getEnableHttps()) {
        builder.usePlaintext();
      } else if (options.getSslContext() != null) {
        builder.sslContext(options.getSslContext());
      } else {
        builder.useTransportSecurity();
      }

      // Disable built-in idleTimer until https://github.com/grpc/grpc-java/issues/8714 is resolved.
      // jsdk force-idles channels often anyway, so this is not needed until we stop doing
      // force-idling as a part of
      // https://github.com/temporalio/sdk-java/issues/888

      // Why 31 days? See ManagedChannelImplBuilder#IDLE_MODE_MAX_TIMEOUT_DAYS and
      // https://github.com/grpc/grpc-java/issues/8714#issuecomment-974389414
      builder.idleTimeout(31, TimeUnit.DAYS);

      this.channel = builder.build();
      // Currently, it is impossible to modify backoff policy on NettyChannelBuilder.
      // For this reason we reset connection backoff every few seconds in order to limit maximum
      // retry interval, which by default equals to 2 minutes.
      // Once https://github.com/grpc/grpc-java/issues/7456 is done we should be able to define
      // custom policy during channel creation and get rid of the code below.
      if (options.getConnectionBackoffResetFrequency() != null) {
        grpcConnectionManager.scheduleWithFixedDelay(
            resetGrpcConnectionBackoffTask(),
            options.getConnectionBackoffResetFrequency().getSeconds(),
            options.getConnectionBackoffResetFrequency().getSeconds(),
            TimeUnit.SECONDS);
      }
      if (options.getGrpcReconnectFrequency() != null) {
        grpcConnectionManager.scheduleWithFixedDelay(
            enterGrpcIdleChannelStateTask(),
            options.getGrpcReconnectFrequency().getSeconds(),
            options.getGrpcReconnectFrequency().getSeconds(),
            TimeUnit.SECONDS);
      }
      channelNeedsShutdown = true;
    }

    Channel interceptedChannel = channel;

    interceptedChannel = applyCustomInterceptors(interceptedChannel);
    interceptedChannel = applyStandardInterceptors(interceptedChannel);

    this.blockingStub = WorkflowServiceGrpc.newBlockingStub(interceptedChannel);
    this.futureStub = WorkflowServiceGrpc.newFutureStub(interceptedChannel);
    if (!options.getDisableHealthCheck()) {
      checkHealth(interceptedChannel);
    }
    log.info(String.format("Created GRPC client for channel: %s", channel));
  }

  private Channel applyStandardInterceptors(Channel channel) {
    GrpcMetricsInterceptor metricsInterceptor =
        new GrpcMetricsInterceptor(options.getMetricsScope());
    ClientInterceptor deadlineInterceptor = new GrpcDeadlineInterceptor(options);
    GrpcTracingInterceptor tracingInterceptor = new GrpcTracingInterceptor();
    Metadata headers = new Metadata();
    headers.merge(options.getHeaders());
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
    interceptedChannel = applyGrpcMetadataProviderInterceptors(interceptedChannel);
    return interceptedChannel;
  }

  private Channel applyGrpcMetadataProviderInterceptors(Channel channel) {
    Collection<GrpcMetadataProvider> grpcMetadataProviders = options.getGrpcMetadataProviders();
    if (grpcMetadataProviders != null && !grpcMetadataProviders.isEmpty()) {
      GrpcMetadataProviderInterceptor grpcMetadataProviderInterceptor =
          new GrpcMetadataProviderInterceptor(grpcMetadataProviders);
      channel = ClientInterceptors.intercept(channel, grpcMetadataProviderInterceptor);
    }
    return channel;
  }

  private Channel applyCustomInterceptors(Channel channel) {
    Collection<ClientInterceptor> grpcClientInterceptors = options.getGrpcClientInterceptors();
    if (grpcClientInterceptors != null) {
      for (ClientInterceptor interceptor : grpcClientInterceptors) {
        channel = ClientInterceptors.intercept(channel, interceptor);
      }
    }
    return channel;
  }

  private Runnable enterGrpcIdleChannelStateTask() {
    return () -> {
      try {
        log.debug("Entering IDLE state on the gRPC channel.");
        channel.enterIdle();
      } catch (Exception e) {
        log.warn("Unable to enter IDLE state on the gRPC channel.", e);
      }
    };
  }

  private Runnable resetGrpcConnectionBackoffTask() {
    return () -> {
      try {
        log.debug("Resetting gRPC connection backoff.");
        channel.resetConnectBackoff();
      } catch (Exception e) {
        log.warn("Unable to reset gRPC connection backoff.", e);
      }
    };
  }

  private ScheduledExecutorService grpcConnectionManager() {
    return Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder()
            .setDaemon(true)
            .setNameFormat("grpc-connection-manager-thread-%d")
            .build());
  }

  /*
   * Checks service health using gRPC health check:
   * https://github.com/grpc/grpc/blob/master/doc/health-checking.md
   *
   * @throws StatusRuntimeException if the service is unavailable.
   * @throws RuntimeException if the check returns unhealthy status.
   * @return true if server is up.
   */
  private void checkHealth(Channel channel) {
    RpcRetryOptions retryOptions =
        RpcRetryOptions.newBuilder()
            .setExpiration(getOptions().getHealthCheckTimeout())
            .validateBuildWithDefaults();
    HealthGrpc.HealthBlockingStub healthBlockingStub = HealthGrpc.newBlockingStub(channel);
    HealthCheckResponse response =
        GrpcRetryer.retryWithResult(
            retryOptions,
            () ->
                healthBlockingStub
                    .withDeadline(
                        Deadline.after(
                            options.getHealthCheckAttemptTimeout().getSeconds(), TimeUnit.SECONDS))
                    .check(
                        HealthCheckRequest.newBuilder()
                            .setService(WorkflowServiceStubsImpl.HEALTH_CHECK_SERVICE_NAME)
                            .build()));

    if (!HealthCheckResponse.ServingStatus.SERVING.equals(response.getStatus())) {
      throw new RuntimeException("Health check returned unhealthy status: " + response.getStatus());
    }
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
    if (grpcConnectionManager != null) {
      grpcConnectionManager.shutdown();
    }
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
    if (grpcConnectionManager != null) {
      grpcConnectionManager.shutdownNow();
    }
    if (channelNeedsShutdown) {
      channel.shutdownNow();
    }
    if (inProcessServer != null) {
      inProcessServer.shutdownNow();
    }
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) {
    try {
      long start = System.currentTimeMillis();
      long deadline = start + unit.toMillis(timeout);
      long left = deadline - System.currentTimeMillis();
      if (grpcConnectionManager != null) {
        grpcConnectionManager.awaitTermination(left, TimeUnit.MILLISECONDS);
      }
      left = deadline - System.currentTimeMillis();
      if (channelNeedsShutdown) {
        return channel.awaitTermination(left, unit);
      }
      left = deadline - System.currentTimeMillis();
      if (inProcessServer != null) {
        inProcessServer.awaitTermination(left, TimeUnit.MILLISECONDS);
      }
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
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
    if (grpcConnectionManager != null) {
      result = result && grpcConnectionManager.isShutdown();
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
    if (grpcConnectionManager != null) {
      result = result && grpcConnectionManager.isTerminated();
    }
    return result;
  }
}
