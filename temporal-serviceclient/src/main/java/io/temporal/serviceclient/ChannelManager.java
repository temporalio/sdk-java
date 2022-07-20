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

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.*;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.internal.retryer.GrpcRetryer;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ChannelManager {
  private static final Logger log = LoggerFactory.getLogger(ChannelManager.class);

  /**
   * This value sets the limit on the incoming responses from Temporal Server to Java SDK. It
   * doesn't affect the limit that Temporal Server exposes on the messages coming to Temporal
   * Frontend from Java SDK. The Server Frontend limit on incoming gRPC message value is currently
   * 4Mb.
   */
  private static final int MAX_INBOUND_MESSAGE_SIZE = 128 * 1024 * 1024;

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

  private final ServiceStubsOptions options;

  private final AtomicBoolean shutdownRequested = new AtomicBoolean();
  // Shutdown channel that was created by us
  private final boolean channelNeedsShutdown;
  private final ScheduledExecutorService grpcConnectionManager;

  private final ManagedChannel rawChannel;
  private final Channel interceptedChannel;
  private final HealthGrpc.HealthBlockingStub healthBlockingStub;

  private final CompletableFuture<GetSystemInfoResponse.Capabilities> serverCapabilitiesFuture =
      new CompletableFuture<>();

  public ChannelManager(
      ServiceStubsOptions options, List<ClientInterceptor> additionalHeadInterceptors) {
    // Do not shutdown a channel passed to the constructor from outside
    this.channelNeedsShutdown = options.getChannel() == null;

    this.options = options;
    if (options.getChannel() != null) {
      this.rawChannel = options.getChannel();
      this.grpcConnectionManager = null;
    } else {
      this.rawChannel = prepareChannel();

      this.grpcConnectionManager = grpcConnectionManager();
      // we can't do it for externally passed channel safely because of grpc race condition bug
      // https://github.com/grpc/grpc-java/issues/8714
      // that requires us to disable built-in idle timer to avoid the race
      initConnectionManagement();
    }

    Channel interceptedChannel = rawChannel;

    interceptedChannel = applyTailStandardInterceptors(interceptedChannel);
    interceptedChannel = applyCustomInterceptors(interceptedChannel);
    interceptedChannel = applyHeadStandardInterceptors(interceptedChannel);
    interceptedChannel =
        ClientInterceptors.intercept(interceptedChannel, additionalHeadInterceptors);
    this.interceptedChannel = interceptedChannel;
    this.healthBlockingStub = HealthGrpc.newBlockingStub(interceptedChannel);
  }

  public ManagedChannel getRawChannel() {
    return rawChannel;
  }

  public Channel getInterceptedChannel() {
    return interceptedChannel;
  }

  /** These interceptors will be called last in the interceptors chain */
  private Channel applyTailStandardInterceptors(Channel channel) {
    GrpcMetricsInterceptor metricsInterceptor =
        new GrpcMetricsInterceptor(options.getMetricsScope());

    channel = ClientInterceptors.intercept(channel, metricsInterceptor);

    // if this interceptor is enabled, it should be added first or in front of any requests
    // modifying interceptors
    // to have the access to fully formed requests
    if (GrpcTracingInterceptor.isEnabled()) {
      GrpcTracingInterceptor tracingInterceptor = new GrpcTracingInterceptor();
      channel = ClientInterceptors.intercept(channel, tracingInterceptor);
    }

    return channel;
  }

  /** These interceptors will be called first in the interceptors chain */
  private Channel applyHeadStandardInterceptors(Channel channel) {
    Metadata headers = new Metadata();
    headers.merge(options.getHeaders());
    headers.put(LIBRARY_VERSION_HEADER_KEY, Version.LIBRARY_VERSION);
    headers.put(SUPPORTED_SERVER_VERSIONS_HEADER_KEY, Version.SUPPORTED_SERVER_VERSIONS);
    headers.put(CLIENT_NAME_HEADER_KEY, CLIENT_NAME_HEADER_VALUE);

    return ClientInterceptors.intercept(
        channel,
        MetadataUtils.newAttachHeadersInterceptor(headers),
        new SystemInfoInterceptor(serverCapabilitiesFuture));
  }

  private Channel applyCustomInterceptors(Channel channel) {
    Collection<ClientInterceptor> grpcClientInterceptors = options.getGrpcClientInterceptors();
    if (grpcClientInterceptors != null) {
      for (ClientInterceptor interceptor : grpcClientInterceptors) {
        channel = ClientInterceptors.intercept(channel, interceptor);
      }
    }

    // should be after grpcClientInterceptors to be closer to the head and to let the
    // grpcClientInterceptors
    // observe requests with grpcClientInterceptors already set
    Collection<GrpcMetadataProvider> grpcMetadataProviders = options.getGrpcMetadataProviders();
    if (grpcMetadataProviders != null && !grpcMetadataProviders.isEmpty()) {
      GrpcMetadataProviderInterceptor grpcMetadataProviderInterceptor =
          new GrpcMetadataProviderInterceptor(grpcMetadataProviders);
      channel = ClientInterceptors.intercept(channel, grpcMetadataProviderInterceptor);
    }
    return channel;
  }

  private ManagedChannel prepareChannel() {
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

    if (options.getChannelInitializer() != null) {
      options.getChannelInitializer().accept(builder);
    }

    return builder.build();
  }

  private void initConnectionManagement() {
    // Currently, it is impossible to modify backoff policy on NettyChannelBuilder.
    // For this reason we reset connection backoff every few seconds in order to limit maximum
    // retry interval, which by default equals to 2 minutes.
    // Once https://github.com/grpc/grpc-java/issues/7456 is done we should be able to define
    // custom policy during channel creation and get rid of the code below.
    if (options.getConnectionBackoffResetFrequency() != null) {
      grpcConnectionManager.scheduleWithFixedDelay(
          resetGrpcConnectionBackoffTask(),
          options.getConnectionBackoffResetFrequency().toMillis(),
          options.getConnectionBackoffResetFrequency().toMillis(),
          TimeUnit.MILLISECONDS);
    }
    if (options.getGrpcReconnectFrequency() != null) {
      grpcConnectionManager.scheduleWithFixedDelay(
          enterGrpcIdleChannelStateTask(),
          options.getGrpcReconnectFrequency().toMillis(),
          options.getGrpcReconnectFrequency().toMillis(),
          TimeUnit.MILLISECONDS);
    }
  }

  private Runnable enterGrpcIdleChannelStateTask() {
    return () -> {
      try {
        log.debug("Entering IDLE state on the gRPC channel {}", rawChannel);
        rawChannel.enterIdle();
      } catch (Exception e) {
        log.warn("Unable to enter IDLE state on the gRPC channel.", e);
      }
    };
  }

  private Runnable resetGrpcConnectionBackoffTask() {
    return () -> {
      try {
        log.debug("Resetting gRPC connection backoff on the gRPC channel {}", rawChannel);
        rawChannel.resetConnectBackoff();
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
   * Establish a connection to the server and ensures that the server is reachable. Throws if the
   * server can't be reached after the specified {@code timeout}.
   *
   * @param timeout how long to wait for a successful connection with the server. If null,
   *     rpcTimeout configured for this stub will be used.
   * @throws StatusRuntimeException if the service is unavailable after {@code timeout}
   * @throws IllegalStateException if the channel is already shutdown
   */
  public void connect(String healthCheckServiceName, @Nullable Duration timeout) {
    ConnectivityState currentState = rawChannel.getState(false);
    if (ConnectivityState.READY.equals(currentState)) {
      return;
    }
    if (ConnectivityState.SHUTDOWN.equals(currentState)) {
      throw new IllegalStateException("Can't connect stubs in SHUTDOWN state");
    }
    if (timeout == null) {
      timeout = options.getRpcTimeout();
    }
    RpcRetryOptions retryOptions =
        RpcRetryOptions.newBuilder().setExpiration(timeout).validateBuildWithDefaults();

    GrpcRetryer.retryWithResult(() -> this.healthCheck(healthCheckServiceName, null), retryOptions);
  }

  /**
   * Checks service health using gRPC standard Health Check:
   * https://github.com/grpc/grpc/blob/master/doc/health-checking.md
   *
   * <p>Please note that this method throws if the Health Check service can't be reached.
   *
   * @param healthCheckServiceName a target service name for the health check request
   * @param timeout custom timeout for the healthcheck
   * @throws StatusRuntimeException if the service is unavailable.
   * @return gRPC Health {@link HealthCheckResponse}
   */
  public HealthCheckResponse healthCheck(
      String healthCheckServiceName, @Nullable Duration timeout) {
    HealthGrpc.HealthBlockingStub stub;
    if (timeout != null) {
      stub =
          this.healthBlockingStub.withDeadline(
              Deadline.after(
                  options.getHealthCheckAttemptTimeout().toMillis(), TimeUnit.MILLISECONDS));
    } else {
      stub = this.healthBlockingStub;
    }
    return stub.check(HealthCheckRequest.newBuilder().setService(healthCheckServiceName).build());
  }

  public GetSystemInfoResponse.Capabilities getServerCapabilities() {
    GetSystemInfoResponse.Capabilities capabilities = serverCapabilitiesFuture.getNow(null);
    if (capabilities == null) {
      serverCapabilitiesFuture.complete(
          SystemInfoInterceptor.getServerCapabilitiesOrThrow(interceptedChannel, null));
    }
    return capabilities;
  }

  public void shutdown() {
    shutdownRequested.set(true);
    if (grpcConnectionManager != null) {
      grpcConnectionManager.shutdown();
    }
    if (channelNeedsShutdown) {
      rawChannel.shutdown();
    }
  }

  public void shutdownNow() {
    shutdownRequested.set(true);
    if (grpcConnectionManager != null) {
      grpcConnectionManager.shutdownNow();
    }
    if (channelNeedsShutdown) {
      rawChannel.shutdownNow();
    }
  }

  public boolean isShutdown() {
    boolean result;
    if (channelNeedsShutdown) {
      result = rawChannel.isShutdown();
    } else {
      result = shutdownRequested.get();
    }
    if (grpcConnectionManager != null) {
      result = result && grpcConnectionManager.isShutdown();
    }
    return result;
  }

  public boolean isTerminated() {
    boolean result;
    if (channelNeedsShutdown) {
      result = rawChannel.isTerminated();
    } else {
      result = shutdownRequested.get();
    }
    if (grpcConnectionManager != null) {
      result = result && grpcConnectionManager.isTerminated();
    }
    return result;
  }

  public boolean awaitTermination(long timeout, TimeUnit unit) {
    try {
      long start = System.currentTimeMillis();
      long left = unit.toMillis(timeout);
      long deadline = start + left;
      if (grpcConnectionManager != null) {
        if (!grpcConnectionManager.awaitTermination(left, TimeUnit.MILLISECONDS)) {
          return false;
        }
      }

      left = deadline - System.currentTimeMillis();
      if (channelNeedsShutdown) {
        return rawChannel.awaitTermination(left, unit);
      }
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
