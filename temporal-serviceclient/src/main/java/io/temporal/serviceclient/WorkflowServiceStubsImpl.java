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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import java.io.IOException;
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
  private final HealthGrpc.HealthBlockingStub healthBlockingStub;
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

      this.channel = builder.build();
      // Currently it is impossible to modify backoff policy on NettyChannelBuilder.
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

    healthBlockingStub = HealthGrpc.newBlockingStub(channel);
    checkHealth();

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

  /**
   * Checks service health using gRPC health check:
   * https://github.com/grpc/grpc/blob/master/doc/health-checking.md
   *
   * @throws StatusRuntimeException if the service is unavailable.
   * @throws RuntimeException if the check returns unhealthy status.
   * @return true if server is up.
   */
  private void checkHealth() {
    checkHealth(HEALTH_CHECK_SERVICE_NAME);
  }

  @VisibleForTesting
  void checkHealth(String serviceName) {
    if (!options.getDisableHealthCheck()) {
      RpcRetryOptions retryOptions =
          RpcRetryOptions.newBuilder()
              .setExpiration(getOptions().getHealthCheckTimeout())
              .validateBuildWithDefaults();

      HealthCheckResponse response =
          GrpcRetryer.retryWithResult(
              retryOptions,
              () -> {
                return healthBlockingStub
                    .withDeadline(
                        Deadline.after(
                            options.getHealthCheckAttemptTimeout().getSeconds(), TimeUnit.SECONDS))
                    .check(HealthCheckRequest.newBuilder().setService(serviceName).build());
              });

      if (!HealthCheckResponse.ServingStatus.SERVING.equals(response.getStatus())) {
        throw new RuntimeException(
            "Health check returned unhealthy status: " + response.getStatus());
      }
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
    if (channelNeedsShutdown) {
      channel.shutdown();
    }
    if (inProcessServer != null) {
      inProcessServer.shutdown();
    }
    if (grpcConnectionManager != null) {
      grpcConnectionManager.shutdown();
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
    if (grpcConnectionManager != null) {
      grpcConnectionManager.shutdownNow();
    }
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) {
    try {
      long start = System.currentTimeMillis();
      if (channelNeedsShutdown) {
        return channel.awaitTermination(timeout, unit);
      }
      long left = System.currentTimeMillis() - unit.toMillis(start);
      if (inProcessServer != null) {
        inProcessServer.awaitTermination(left, TimeUnit.MILLISECONDS);
      }
      left = System.currentTimeMillis() - unit.toMillis(start);
      if (grpcConnectionManager != null) {
        grpcConnectionManager.awaitTermination(left, TimeUnit.MILLISECONDS);
      }
      return true;
    } catch (InterruptedException e) {
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
