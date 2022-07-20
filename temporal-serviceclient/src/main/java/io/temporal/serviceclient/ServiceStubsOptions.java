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

import com.google.common.base.MoreObjects;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.grpc.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class ServiceStubsOptions {
  public static final String DEFAULT_LOCAL_DOCKER_TARGET = "127.0.0.1:7233";

  /** Default RPC timeout used for all non-long-poll and non-query calls. */
  public static final Duration DEFAULT_RPC_TIMEOUT = Duration.ofSeconds(10);

  /** Default timeout that will be used to reset connection backoff. */
  public static final Duration DEFAULT_CONNECTION_BACKOFF_RESET_FREQUENCY = Duration.ofSeconds(10);

  /**
   * Default timeout that will be used to enter idle channel state and reconnect to temporal server.
   */
  public static final Duration DEFAULT_GRPC_RECONNECT_FREQUENCY = Duration.ofMinutes(1);

  protected final ManagedChannel channel;

  /**
   * target string to use for connection/channel in {@link ManagedChannelBuilder#forTarget(String)}
   */
  protected final String target;

  protected final @Nullable Consumer<ManagedChannelBuilder<?>> channelInitializer;

  /** Indicates whether basic HTTPS/SSL/TLS should be enabled * */
  protected final boolean enableHttps;

  /** The user provided context for SSL/TLS over gRPC * */
  protected final SslContext sslContext;

  /**
   * HealthCheckAttemptTimeout specifies how to long to wait for service response on each health
   * check attempt. Default: 5s.
   */
  protected final Duration healthCheckAttemptTimeout;

  /**
   * HealthCheckTimeout defines how long client should be sending health check requests to the
   * server before concluding that it is unavailable. Defaults to 10s.
   */
  protected final Duration healthCheckTimeout;

  /**
   * Enables keep alive ping from client to the server, which can help drop abruptly closed
   * connections faster.
   */
  protected final boolean enableKeepAlive;

  /**
   * Interval at which server will be pinged in order to determine if connections are still alive.
   */
  protected final Duration keepAliveTime;
  /**
   * Amount of time that client would wait for the keep alive ping response from the server before
   * closing the connection.
   */
  protected final Duration keepAliveTimeout;

  /** If true, keep alive ping will be allowed when there are no active RPCs. */
  protected final boolean keepAlivePermitWithoutStream;

  /** The gRPC timeout */
  protected final Duration rpcTimeout;

  /** Frequency at which connection backoff is going to be reset */
  protected final Duration connectionBackoffResetFrequency;

  /**
   * Frequency at which grpc connection channel will be moved into an idle state, triggering a new
   * connection to the temporal frontend host.
   */
  protected final Duration grpcReconnectFrequency;

  /** Optional gRPC headers */
  protected final Metadata headers;

  /**
   * gRPC metadata/headers providers to be called on each gRPC request to supply additional headers
   */
  protected final Collection<GrpcMetadataProvider> grpcMetadataProviders;

  /** gRPC client interceptors to be added to gRPC channel */
  protected final Collection<ClientInterceptor> grpcClientInterceptors;

  protected final Scope metricsScope;

  ServiceStubsOptions(ServiceStubsOptions that) {
    this.channel = that.channel;
    this.target = that.target;
    this.channelInitializer = that.channelInitializer;
    this.enableHttps = that.enableHttps;
    this.sslContext = that.sslContext;
    this.healthCheckAttemptTimeout = that.healthCheckAttemptTimeout;
    this.healthCheckTimeout = that.healthCheckTimeout;
    this.enableKeepAlive = that.enableKeepAlive;
    this.keepAliveTime = that.keepAliveTime;
    this.keepAliveTimeout = that.keepAliveTimeout;
    this.keepAlivePermitWithoutStream = that.keepAlivePermitWithoutStream;
    this.rpcTimeout = that.rpcTimeout;
    this.connectionBackoffResetFrequency = that.connectionBackoffResetFrequency;
    this.grpcReconnectFrequency = that.grpcReconnectFrequency;
    this.headers = that.headers;
    this.grpcMetadataProviders = that.grpcMetadataProviders;
    this.grpcClientInterceptors = that.grpcClientInterceptors;
    this.metricsScope = that.metricsScope;
  }

  ServiceStubsOptions(
      ManagedChannel channel,
      String target,
      @Nullable Consumer<ManagedChannelBuilder<?>> channelInitializer,
      boolean enableHttps,
      SslContext sslContext,
      Duration healthCheckAttemptTimeout,
      Duration healthCheckTimeout,
      boolean enableKeepAlive,
      Duration keepAliveTime,
      Duration keepAliveTimeout,
      boolean keepAlivePermitWithoutStream,
      Duration rpcTimeout,
      Duration connectionBackoffResetFrequency,
      Duration grpcReconnectFrequency,
      Metadata headers,
      Collection<GrpcMetadataProvider> grpcMetadataProviders,
      Collection<ClientInterceptor> grpcClientInterceptors,
      Scope metricsScope) {
    this.channel = channel;
    this.target = target;
    this.channelInitializer = channelInitializer;
    this.enableHttps = enableHttps;
    this.sslContext = sslContext;
    this.healthCheckAttemptTimeout = healthCheckAttemptTimeout;
    this.healthCheckTimeout = healthCheckTimeout;
    this.enableKeepAlive = enableKeepAlive;
    this.keepAliveTime = keepAliveTime;
    this.keepAliveTimeout = keepAliveTimeout;
    this.keepAlivePermitWithoutStream = keepAlivePermitWithoutStream;
    this.rpcTimeout = rpcTimeout;
    this.connectionBackoffResetFrequency = connectionBackoffResetFrequency;
    this.grpcReconnectFrequency = grpcReconnectFrequency;
    this.headers = headers;
    this.grpcMetadataProviders = grpcMetadataProviders;
    this.grpcClientInterceptors = grpcClientInterceptors;
    this.metricsScope = metricsScope;
  }

  /**
   * @return fully custom user-configured externally provided gRPC channel to use
   * @see Builder#setChannel(ManagedChannel)
   */
  public ManagedChannel getChannel() {
    return channel;
  }

  /**
   * @return the target string to use for connection/channel in {@link
   *     ManagedChannelBuilder#forTarget(String)}
   */
  public String getTarget() {
    return target;
  }

  /**
   * Gives an opportunity to provide some additional configuration to the channel builder or
   * override configurations done by the Temporal Stubs.
   *
   * <p>Advanced API
   *
   * @return listener that will be called as a last step of channel creation if the channel is
   *     configured by {@link Builder#setTarget(String)}.
   */
  @Nullable
  public Consumer<ManagedChannelBuilder<?>> getChannelInitializer() {
    return channelInitializer;
  }

  /**
   * @return if gRPC should use SSL/TLS; Ignored and assumed {@code true} if {@link
   *     #getSslContext()} is set
   */
  public boolean getEnableHttps() {
    return enableHttps;
  }

  /**
   * @return the gRPC SSL Context to use
   */
  public SslContext getSslContext() {
    return sslContext;
  }

  /**
   * @return how to long to wait for service response on each health check attempt
   */
  public Duration getHealthCheckAttemptTimeout() {
    return healthCheckAttemptTimeout;
  }

  /**
   * @return duration of time to wait while checking server connection when creating new client
   */
  public Duration getHealthCheckTimeout() {
    return healthCheckTimeout;
  }

  /**
   * @return true if ping from client to the server is enabled, which can help detect and drop
   *     abruptly closed connections faster
   */
  public boolean getEnableKeepAlive() {
    return enableKeepAlive;
  }

  /**
   * @return interval at which server will be pinged in order to determine if connections are still
   *     alive
   */
  public Duration getKeepAliveTime() {
    return keepAliveTime;
  }

  /**
   * @return amount of time that client would wait for the keep alive ping response from the server
   *     before closing the connection
   */
  public Duration getKeepAliveTimeout() {
    return keepAliveTimeout;
  }

  /**
   * @return if keep alive ping will be allowed when there are no active RPCs
   */
  public boolean getKeepAlivePermitWithoutStream() {
    return keepAlivePermitWithoutStream;
  }

  /**
   * @return the rpc timeout value
   */
  public Duration getRpcTimeout() {
    return rpcTimeout;
  }

  /**
   * @return frequency at which connection backoff should be reset or null if backoff reset is
   *     disabled
   */
  public Duration getConnectionBackoffResetFrequency() {
    return connectionBackoffResetFrequency;
  }

  /**
   * @return frequency at which grpc channel should be moved into an idle state
   */
  public Duration getGrpcReconnectFrequency() {
    return grpcReconnectFrequency;
  }

  /**
   * @return gRPC headers to be added to every call
   */
  public Metadata getHeaders() {
    return headers;
  }

  /**
   * @return gRPC metadata/headers providers to be called on each gRPC request to supply additional
   *     headers
   */
  public Collection<GrpcMetadataProvider> getGrpcMetadataProviders() {
    return grpcMetadataProviders;
  }

  /**
   * @return gRPC client interceptors to be added to gRPC channel
   */
  public Collection<ClientInterceptor> getGrpcClientInterceptors() {
    return grpcClientInterceptors;
  }

  /**
   * @return scope to be used for metrics reporting
   */
  @Nonnull
  public Scope getMetricsScope() {
    return metricsScope;
  }

  static class Builder<T extends Builder<T>> {
    private ManagedChannel channel;
    private SslContext sslContext;
    private boolean enableHttps;
    private String target;
    private Consumer<ManagedChannelBuilder<?>> channelInitializer;
    private Duration healthCheckAttemptTimeout;
    private Duration healthCheckTimeout;
    private boolean enableKeepAlive;
    private Duration keepAliveTime;
    private Duration keepAliveTimeout;
    private boolean keepAlivePermitWithoutStream;
    private Duration rpcTimeout = DEFAULT_RPC_TIMEOUT;
    private Duration connectionBackoffResetFrequency = DEFAULT_CONNECTION_BACKOFF_RESET_FREQUENCY;
    private Duration grpcReconnectFrequency = DEFAULT_GRPC_RECONNECT_FREQUENCY;
    private Metadata headers;
    private Collection<GrpcMetadataProvider> grpcMetadataProviders;
    private Collection<ClientInterceptor> grpcClientInterceptors;
    private Scope metricsScope;

    protected Builder() {}

    protected Builder(ServiceStubsOptions options) {
      this.channel = options.channel;
      this.target = options.target;
      this.channelInitializer = options.channelInitializer;
      this.enableHttps = options.enableHttps;
      this.sslContext = options.sslContext;
      this.healthCheckAttemptTimeout = options.healthCheckAttemptTimeout;
      this.healthCheckTimeout = options.healthCheckTimeout;
      this.enableKeepAlive = options.enableKeepAlive;
      this.keepAliveTime = options.keepAliveTime;
      this.keepAliveTimeout = options.keepAliveTimeout;
      this.keepAlivePermitWithoutStream = options.keepAlivePermitWithoutStream;
      this.rpcTimeout = options.rpcTimeout;
      this.connectionBackoffResetFrequency = options.connectionBackoffResetFrequency;
      this.grpcReconnectFrequency = options.grpcReconnectFrequency;
      this.headers = options.headers;
      this.grpcMetadataProviders = options.grpcMetadataProviders;
      this.grpcClientInterceptors = options.grpcClientInterceptors;
      this.metricsScope = options.metricsScope;
    }

    /**
     * Sets a target string, which can be either a valid {@link NameResolver}-compliant URI, or an
     * authority string. See {@link ManagedChannelBuilder#forTarget(String)} for more information
     * about parameter format. Default is {@link #DEFAULT_LOCAL_DOCKER_TARGET}
     *
     * <p>Mutually exclusive with {@link #setChannel(ManagedChannel)}.
     *
     * @return {@code this}
     */
    public T setTarget(String target) {
      this.target = target;
      return self();
    }

    /**
     * Gives an opportunity to provide some additional configuration to the channel builder or
     * override configurations done by the Temporal Stubs. Currently, Temporal Stubs use {@link
     * io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder} to create a {@link ManagedChannel}.
     *
     * <p>Advanced API
     *
     * <p>Mutually exclusive with {@link #setChannel(ManagedChannel)}.
     *
     * @param channelInitializer listener that will be called as a last step of channel creation if
     *     Stubs are configured with {@link Builder#setTarget(String)}. The listener is called with
     *     an instance of {@link io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder} that will
     *     be used by Temporal Stubs to create a {@link ManagedChannel}. The builder type may change
     *     in the future.
     * @return {@code this}
     */
    public T setChannelInitializer(Consumer<ManagedChannelBuilder<?>> channelInitializer) {
      this.channelInitializer = channelInitializer;
      return self();
    }

    /**
     * Sets fully custom user-configured gRPC channel to use.
     *
     * <p>Before supplying a fully custom channel using this method, it's recommended to first
     * consider using {@link #setTarget(String)} + other options of {@link
     * WorkflowServiceStubsOptions.Builder} + {@link #setChannelInitializer(Consumer)} for some
     * rarely used configuration.<br>
     * This option is not intended for the majority of users as it disables some Temporal connection
     * management features and can lead to outages if the channel is configured or managed
     * improperly.
     *
     * <p>Mutually exclusive with {@link #setTarget(String)}, {@link
     * #setChannelInitializer(Consumer)}, {@link #setSslContext(SslContext)}, {@link
     * #setGrpcReconnectFrequency(Duration)} and {@link
     * #setConnectionBackoffResetFrequency(Duration)}. These options are ignored if the custom
     * channel is supplied.
     *
     * @return {@code this}
     */
    public T setChannel(ManagedChannel channel) {
      this.channel = channel;
      return self();
    }

    /**
     * Sets gRPC SSL Context to use, used for more advanced scenarios such as mTLS. Supersedes
     * enableHttps; Exclusive with channel. Consider using {@link SimpleSslContextBuilder} which
     * greatly simplifies creation of the TLS enabled SslContext with client and server key
     * validation.
     *
     * @return {@code this}
     */
    public T setSslContext(SslContext sslContext) {
      this.sslContext = sslContext;
      return self();
    }

    /**
     * Sets option to enable SSL/TLS/HTTPS for gRPC.
     *
     * <p>Mutually exclusive with channel; Ignored and assumed {@code true} if {@link
     * #setSslContext(SslContext)} is specified.
     *
     * @return {@code this}
     */
    public T setEnableHttps(boolean enableHttps) {
      this.enableHttps = enableHttps;
      return self();
    }

    /**
     * Sets frequency at which gRPC connection backoff should be reset practically defining an upper
     * limit for the maximum backoff duration. If set to null then no backoff reset will be
     * performed and we'll rely on default gRPC backoff behavior defined in
     * ExponentialBackoffPolicy.
     *
     * <p>Mutually exclusive with {@link #setChannel(ManagedChannel)}.
     *
     * @param connectionBackoffResetFrequency frequency, defaults to once every 10 seconds. Set to
     *     null in order to disable this feature
     * @see ManagedChannel#resetConnectBackoff()
     * @return {@code this}
     */
    public T setConnectionBackoffResetFrequency(Duration connectionBackoffResetFrequency) {
      this.connectionBackoffResetFrequency = connectionBackoffResetFrequency;
      return self();
    }

    /**
     * Sets frequency at which gRPC channel will be moved into an idle state and triggers tear-down
     * of the channel's name resolver and load balancer, while still allowing on-going RPCs on the
     * channel to continue. New RPCs on the channel will trigger creation of a new connection. This
     * allows worker to connect to a new temporal backend host periodically avoiding hot spots and
     * resulting in a more even connection distribution.
     *
     * <p>Mutually exclusive with {@link #setChannel(ManagedChannel)}.
     *
     * @param grpcReconnectFrequency frequency, defaults to once every 1 minute. Set to null in
     *     order to disable this feature
     * @see ManagedChannel#enterIdle()
     * @return {@code this}
     */
    public T setGrpcReconnectFrequency(Duration grpcReconnectFrequency) {
      this.grpcReconnectFrequency = grpcReconnectFrequency;
      return self();
    }

    /**
     * @param headers gRPC headers to be added to every call
     * @return {@code this}
     */
    public T setHeaders(Metadata headers) {
      this.headers = headers;
      return self();
    }

    /**
     * @param grpcMetadataProvider gRPC metadata/headers provider to be called on each gRPC request
     *     to supply additional headers
     * @return {@code this}
     */
    public T addGrpcMetadataProvider(GrpcMetadataProvider grpcMetadataProvider) {
      if (this.grpcMetadataProviders == null) {
        this.grpcMetadataProviders = new ArrayList<>();
      }
      this.grpcMetadataProviders.add(grpcMetadataProvider);
      return self();
    }

    /**
     * @param grpcMetadataProviders gRPC metadata/headers providers to be called on each gRPC
     *     request to supply additional headers
     * @return {@code this}
     */
    public T setGrpcMetadataProviders(Collection<GrpcMetadataProvider> grpcMetadataProviders) {
      this.grpcMetadataProviders = grpcMetadataProviders;
      return self();
    }

    /**
     * @param grpcClientInterceptor gRPC client interceptor to be added to gRPC channel
     * @return {@code this}
     */
    public T addGrpcClientInterceptor(ClientInterceptor grpcClientInterceptor) {
      if (this.grpcClientInterceptors == null) {
        grpcClientInterceptors = new ArrayList<>();
      }
      this.grpcClientInterceptors.add(grpcClientInterceptor);
      return self();
    }

    /**
     * @param grpcClientInterceptors gRPC client interceptors to be added to gRPC channel
     * @return {@code this}
     */
    public T setGrpcClientInterceptors(Collection<ClientInterceptor> grpcClientInterceptors) {
      this.grpcClientInterceptors = grpcClientInterceptors;
      return self();
    }

    /**
     * Sets the scope to be used for metrics reporting. Optional. Default is to not report metrics.
     *
     * <p>This method should be used to integrate client and workers with external metrics and
     * monitoring systems.
     *
     * <p>Example:<br>
     *
     * <pre>{@code
     * PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
     * StatsReporter reporter = new MicrometerClientStatsReporter(registry);
     * Scope scope = new RootScopeBuilder().reporter(reporter).reportEvery(Duration.ofSeconds(10));
     * WorkflowServiceStubsOptions options =
     *     WorkflowServiceStubsOptions.newBuilder()
     *         .setMetricsScope(scope)
     *         .build();
     * }</pre>
     *
     * <p>Note: Don't mock {@link Scope} in tests! If you need to verify the metrics behavior,
     * create a real Scope and mock, stub or spy a reporter instance:<br>
     *
     * <pre>{@code
     * StatsReporter reporter = mock(StatsReporter.class);
     * Scope metricsScope =
     *     new RootScopeBuilder()
     *         .reporter(reporter)
     *         .reportEvery(com.uber.m3.util.Duration.ofMillis(10));
     * }</pre>
     *
     * @param metricsScope the scope to be used for metrics reporting.
     * @return {@code this}
     */
    public T setMetricsScope(Scope metricsScope) {
      this.metricsScope = metricsScope;
      return self();
    }

    /**
     * Set the time to wait between service responses on each health check.
     *
     * @return {@code this}
     * @deprecated {@link #rpcTimeout} is now used as an attempt timeout.
     */
    @Deprecated
    public T setHealthCheckAttemptTimeout(Duration healthCheckAttemptTimeout) {
      this.healthCheckAttemptTimeout = healthCheckAttemptTimeout;
      return self();
    }

    /**
     * Set a HealthCheckTimeout after which to stop waiting while checking server connection when
     * creating new client.
     *
     * @return {@code this}
     * @deprecated Use more explicit {@link
     *     WorkflowServiceStubs#newConnectedServiceStubs(WorkflowServiceStubsOptions, Duration)}
     *     with a timeout parameter instead
     */
    @Deprecated
    public T setHealthCheckTimeout(Duration healthCheckTimeout) {
      this.healthCheckTimeout = healthCheckTimeout;
      return self();
    }

    /**
     * Enables keep alive ping from client to the server, which can help drop abruptly closed
     * connections faster.
     *
     * @return {@code this}
     */
    public T setEnableKeepAlive(boolean enableKeepAlive) {
      this.enableKeepAlive = enableKeepAlive;
      return self();
    }

    /**
     * After a duration of this time if the client doesn't see any activity it pings the server to
     * see if the transport is still alive. If set below 10s, a minimum value of 10s will be used
     * instead.
     *
     * @return {@code this}
     */
    public T setKeepAliveTime(Duration keepAliveTime) {
      this.keepAliveTime = keepAliveTime;
      return self();
    }

    /**
     * After having pinged for keepalive check, the client waits for a duration of Timeout and if no
     * activity is seen even after that the connection is closed.
     *
     * @return {@code this}
     */
    public T setKeepAliveTimeout(Duration keepAliveTimeout) {
      this.keepAliveTimeout = keepAliveTimeout;
      return self();
    }

    /**
     * If true, client sends keepalive pings even with no active RPCs. If false, when there are no
     * active RPCs, Time and Timeout will be ignored and no keepalive pings will be sent. * @return
     *
     * @return {@code this}
     */
    public T setKeepAlivePermitWithoutStream(boolean keepAlivePermitWithoutStream) {
      this.keepAlivePermitWithoutStream = keepAlivePermitWithoutStream;
      return self();
    }

    /**
     * Sets the rpc timeout value. Default is 10 seconds.
     *
     * @return {@code this}
     */
    public T setRpcTimeout(Duration timeout) {
      this.rpcTimeout = Objects.requireNonNull(timeout);
      return self();
    }

    /**
     * @return {@code this}
     */
    @SuppressWarnings("unchecked")
    private T self() {
      return (T) this;
    }

    /**
     * @return Built ServiceStubOptions object with the specified params
     */
    public ServiceStubsOptions build() {
      return new ServiceStubsOptions(
          this.channel,
          this.target,
          this.channelInitializer,
          this.enableHttps,
          this.sslContext,
          this.healthCheckAttemptTimeout,
          this.healthCheckTimeout,
          this.enableKeepAlive,
          this.keepAliveTime,
          this.keepAliveTimeout,
          this.keepAlivePermitWithoutStream,
          this.rpcTimeout,
          this.connectionBackoffResetFrequency,
          this.grpcReconnectFrequency,
          this.headers,
          this.grpcMetadataProviders,
          this.grpcClientInterceptors,
          this.metricsScope);
    }

    public ServiceStubsOptions validateAndBuildWithDefaults() {
      if (this.target != null && this.channel != null) {
        throw new IllegalStateException(
            "Only one of the 'target' or 'channel' options can be set at a time");
      }

      if (this.channelInitializer != null && this.channel != null) {
        throw new IllegalStateException(
            "Only one of the 'channelInitializer' or 'channel' options can be set at a time");
      }

      if (this.sslContext != null && this.channel != null) {
        throw new IllegalStateException(
            "Only one of the 'sslContext' or 'channel' options can be set at a time");
      }

      if (this.enableHttps && this.channel != null) {
        throw new IllegalStateException(
            "Only one of the 'enableHttps' or 'channel' options can be set at a time");
      }

      String target =
          this.target == null && this.channel == null ? DEFAULT_LOCAL_DOCKER_TARGET : this.target;

      Metadata headers = this.headers != null ? this.headers : new Metadata();
      Collection<GrpcMetadataProvider> grpcMetadataProviders =
          MoreObjects.firstNonNull(this.grpcMetadataProviders, Collections.emptyList());
      Collection<ClientInterceptor> grpcClientInterceptors =
          MoreObjects.firstNonNull(this.grpcClientInterceptors, Collections.emptyList());

      Scope metricsScope = this.metricsScope != null ? this.metricsScope : new NoopScope();
      Duration healthCheckAttemptTimeout =
          this.healthCheckAttemptTimeout != null
              ? this.healthCheckAttemptTimeout
              : Duration.ofSeconds(5);
      Duration healthCheckTimeout =
          this.healthCheckTimeout != null ? this.healthCheckTimeout : Duration.ofSeconds(10);

      return new ServiceStubsOptions(
          this.channel,
          target,
          this.channelInitializer,
          this.enableHttps,
          this.sslContext,
          healthCheckAttemptTimeout,
          healthCheckTimeout,
          this.enableKeepAlive,
          this.keepAliveTime,
          this.keepAliveTimeout,
          this.keepAlivePermitWithoutStream,
          this.rpcTimeout,
          this.connectionBackoffResetFrequency,
          this.grpcReconnectFrequency,
          headers,
          grpcMetadataProviders,
          grpcClientInterceptors,
          metricsScope);
    }
  }
}
