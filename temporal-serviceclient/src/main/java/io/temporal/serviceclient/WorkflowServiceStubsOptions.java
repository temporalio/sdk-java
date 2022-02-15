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

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.grpc.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.temporal.serviceclient.rpcretry.DefaultStubServiceOperationRpcRetryOptions;
import java.time.Duration;
import java.util.*;
import javax.annotation.Nullable;

public class WorkflowServiceStubsOptions {

  public static final String DEFAULT_LOCAL_DOCKER_TARGET = "127.0.0.1:7233";

  /** Default RPC timeout used for all non-long-poll and non-query calls. */
  public static final Duration DEFAULT_RPC_TIMEOUT = Duration.ofSeconds(10);
  /**
   * RPC timeout used for all long poll calls on Temporal Server side. Long poll returns with an
   * empty result after this server timeout.
   */
  public static final Duration DEFAULT_SERVER_LONG_POLL_RPC_TIMEOUT = Duration.ofSeconds(60);
  /** Default RPC timeout used for all long poll calls. */
  public static final Duration DEFAULT_POLL_RPC_TIMEOUT =
      DEFAULT_SERVER_LONG_POLL_RPC_TIMEOUT.plus(Duration.ofSeconds(10));
  /** Default RPC timeout for workflow queries */
  public static final Duration DEFAULT_QUERY_RPC_TIMEOUT = Duration.ofSeconds(10);
  /** Default timeout that will be used to reset connection backoff. */
  public static final Duration DEFAULT_CONNECTION_BACKOFF_RESET_FREQUENCY = Duration.ofSeconds(10);
  /**
   * Default timeout that will be used to enter idle channel state and reconnect to temporal server.
   */
  public static final Duration DEFAULT_GRPC_RECONNECT_FREQUENCY = Duration.ofMinutes(1);

  private static final WorkflowServiceStubsOptions DEFAULT_INSTANCE =
      WorkflowServiceStubsOptions.newBuilder().build();

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(WorkflowServiceStubsOptions options) {
    return new Builder(options);
  }

  public static WorkflowServiceStubsOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private final ManagedChannel channel;

  /**
   * target string to use for connection/channel in {@link ManagedChannelBuilder#forTarget(String)}
   */
  private final String target;

  private final @Nullable ChannelInitializer channelInitializer;

  /** The user provided context for SSL/TLS over gRPC * */
  private final SslContext sslContext;

  /** Indicates whether basic HTTPS/SSL/TLS should be enabled * */
  private final boolean enableHttps;

  /**
   * Asks client to perform a health check after gRPC connection to the Server is created by making
   * a request to endpoint to make sure that the server is accessible.
   */
  private final boolean disableHealthCheck;

  /**
   * HealthCheckAttemptTimeout specifies how to long to wait for service response on each health
   * check attempt. Default: 5s.
   */
  private final Duration healthCheckAttemptTimeout;

  /**
   * HealthCheckTimeout defines how long client should be sending health check requests to the
   * server before concluding that it is unavailable. Defaults to 10s.
   */
  private final Duration healthCheckTimeout;

  /**
   * Enables keep alive ping from client to the server, which can help drop abruptly closed
   * connections faster.
   */
  private final boolean enableKeepAlive;

  /**
   * Interval at which server will be pinged in order to determine if connections are still alive.
   */
  private final Duration keepAliveTime;
  /**
   * Amount of time that client would wait for the keep alive ping response from the server before
   * closing the connection.
   */
  private final Duration keepAliveTimeout;

  /** If true, keep alive ping will be allowed when there are no active RPCs. */
  private final boolean keepAlivePermitWithoutStream;

  /** The gRPC timeout */
  private final Duration rpcTimeout;

  /** The gRPC timeout for long poll calls */
  private final Duration rpcLongPollTimeout;

  /** The gRPC timeout for query workflow call */
  private final Duration rpcQueryTimeout;

  /** Retry options for outgoing RPC calls */
  private final RpcRetryOptions rpcRetryOptions;

  /** Frequency at which connection backoff is going to be reset */
  private final Duration connectionBackoffResetFrequency;

  /**
   * Frequency at which grpc connection channel will be moved into an idle state, triggering a new
   * connection to the temporal frontend host.
   */
  private final Duration grpcReconnectFrequency;

  /** Optional gRPC headers */
  private final Metadata headers;

  /**
   * gRPC metadata/headers providers to be called on each gRPC request to supply additional headers
   */
  private final Collection<GrpcMetadataProvider> grpcMetadataProviders;

  /** gRPC client interceptors to be added to gRPC channel */
  private final Collection<ClientInterceptor> grpcClientInterceptors;

  private final Scope metricsScope;

  private WorkflowServiceStubsOptions(
      ManagedChannel channel,
      String target,
      @Nullable ChannelInitializer channelInitializer,
      SslContext sslContext,
      boolean enableHttps,
      boolean disableHealthCheck,
      Duration healthCheckAttemptTimeout,
      Duration healthCheckTimeout,
      boolean enableKeepAlive,
      Duration keepAliveTime,
      Duration keepAliveTimeout,
      boolean keepAlivePermitWithoutStream,
      Duration rpcTimeout,
      Duration rpcLongPollTimeout,
      Duration rpcQueryTimeout,
      RpcRetryOptions rpcRetryOptions,
      Duration connectionBackoffResetFrequency,
      Duration grpcReconnectFrequency,
      Metadata headers,
      Collection<GrpcMetadataProvider> grpcMetadataProviders,
      Collection<ClientInterceptor> grpcClientInterceptors,
      Scope metricsScope) {
    this.channel = channel;
    this.target = target;
    this.channelInitializer = channelInitializer;
    this.sslContext = sslContext;
    this.enableHttps = enableHttps;
    this.disableHealthCheck = disableHealthCheck;
    this.healthCheckAttemptTimeout = healthCheckAttemptTimeout;
    this.healthCheckTimeout = healthCheckTimeout;
    this.enableKeepAlive = enableKeepAlive;
    this.keepAliveTime = keepAliveTime;
    this.keepAliveTimeout = keepAliveTimeout;
    this.keepAlivePermitWithoutStream = keepAlivePermitWithoutStream;
    this.rpcTimeout = rpcTimeout;
    this.rpcLongPollTimeout = rpcLongPollTimeout;
    this.rpcQueryTimeout = rpcQueryTimeout;
    this.rpcRetryOptions = rpcRetryOptions;
    this.connectionBackoffResetFrequency = connectionBackoffResetFrequency;
    this.grpcReconnectFrequency = grpcReconnectFrequency;
    this.headers = headers;
    this.grpcMetadataProviders = grpcMetadataProviders;
    this.grpcClientInterceptors = grpcClientInterceptors;
    this.metricsScope = metricsScope;
  }

  public ManagedChannel getChannel() {
    return channel;
  }

  /**
   * Returns the target string to use for connection/channel in {@link
   * ManagedChannelBuilder#forTarget(String)}
   */
  public String getTarget() {
    return target;
  }

  @Nullable
  public ChannelInitializer getChannelInitializer() {
    return channelInitializer;
  }

  /** @return Returns the gRPC SSL Context to use. * */
  public SslContext getSslContext() {
    return sslContext;
  }

  /** @return Returns a boolean indicating whether gRPC should use SSL/TLS. * */
  public boolean getEnableHttps() {
    return enableHttps;
  }

  /** @return false when client checks endpoint to make sure that the server is accessible. */
  public boolean getDisableHealthCheck() {
    return disableHealthCheck;
  }

  /** @return how to long to wait for service response on each health check attempt. */
  public Duration getHealthCheckAttemptTimeout() {
    return healthCheckAttemptTimeout;
  }

  /** @return duration of time to wait while checking server connection when creating new client. */
  public Duration getHealthCheckTimeout() {
    return healthCheckTimeout;
  }

  /**
   * @return true if ping from client to the server is enabled, which can help detect and drop
   *     abruptly closed connections faster.
   */
  public boolean getEnableKeepAlive() {
    return enableKeepAlive;
  }

  /**
   * @return Interval at which server will be pinged in order to determine if connections are still
   *     alive.
   */
  public Duration getKeepAliveTime() {
    return keepAliveTime;
  }

  /**
   * @return Amount of time that client would wait for the keep alive ping response from the server
   *     before closing the connection.
   */
  public Duration getKeepAliveTimeout() {
    return keepAliveTimeout;
  }

  /** @return If true, keep alive ping will be allowed when there are no active RPCs. */
  public boolean getKeepAlivePermitWithoutStream() {
    return keepAlivePermitWithoutStream;
  }

  /** @return Returns the rpc timeout value. */
  public Duration getRpcTimeout() {
    return rpcTimeout;
  }

  /** @return Returns the rpc timout for long poll requests. */
  public Duration getRpcLongPollTimeout() {
    return rpcLongPollTimeout;
  }

  /** @return Returns the rpc timout for query workflow requests. */
  public Duration getRpcQueryTimeout() {
    return rpcQueryTimeout;
  }

  /**
   * @return Returns rpc retry options for outgoing requests to the temporal server that supposed to
   *     be processed and returned fast, like start workflow (not long polls or awaits for workflow
   *     finishing).
   */
  public RpcRetryOptions getRpcRetryOptions() {
    return rpcRetryOptions;
  }

  /**
   * @return frequency at which connection backoff should be reset or null if backoff reset is
   *     disabled.
   */
  public Duration getConnectionBackoffResetFrequency() {
    return connectionBackoffResetFrequency;
  }

  /** @return frequency at which grpc channel should be moved into an idle state. */
  public Duration getGrpcReconnectFrequency() {
    return grpcReconnectFrequency;
  }

  /** @return gRPC headers to be added to every call. */
  public Metadata getHeaders() {
    return headers;
  }

  /**
   * @return gRPC metadata/headers providers to be called on each gRPC request to supply additional
   *     headers.
   */
  public Collection<GrpcMetadataProvider> getGrpcMetadataProviders() {
    return grpcMetadataProviders;
  }

  /** @return gRPC client interceptors to be added to gRPC channel. */
  public Collection<ClientInterceptor> getGrpcClientInterceptors() {
    return grpcClientInterceptors;
  }

  public Scope getMetricsScope() {
    return metricsScope;
  }

  /**
   * Builder is the builder for ClientOptions.
   *
   * @author venkat
   */
  public static class Builder {

    private ManagedChannel channel;
    private SslContext sslContext;
    private boolean enableHttps;
    private String target;
    private ChannelInitializer channelInitializer;
    private boolean disableHealthCheck;
    private Duration healthCheckAttemptTimeout;
    private Duration healthCheckTimeout;
    private boolean enableKeepAlive;
    private Duration keepAliveTime;
    private Duration keepAliveTimeout;
    private boolean keepAlivePermitWithoutStream;

    private Duration rpcTimeout = DEFAULT_RPC_TIMEOUT;
    private Duration rpcLongPollTimeout = DEFAULT_POLL_RPC_TIMEOUT;
    private Duration rpcQueryTimeout = DEFAULT_QUERY_RPC_TIMEOUT;
    private RpcRetryOptions rpcRetryOptions = DefaultStubServiceOperationRpcRetryOptions.INSTANCE;
    private Duration connectionBackoffResetFrequency = DEFAULT_CONNECTION_BACKOFF_RESET_FREQUENCY;
    private Duration grpcReconnectFrequency = DEFAULT_GRPC_RECONNECT_FREQUENCY;
    private Metadata headers;
    private Collection<GrpcMetadataProvider> grpcMetadataProviders;
    private Collection<ClientInterceptor> grpcClientInterceptors;
    private Scope metricsScope;

    private Builder() {}

    private Builder(WorkflowServiceStubsOptions options) {
      this.target = options.target;
      this.channel = options.channel;
      this.enableHttps = options.enableHttps;
      this.sslContext = options.sslContext;
      this.rpcLongPollTimeout = options.rpcLongPollTimeout;
      this.rpcQueryTimeout = options.rpcQueryTimeout;
      this.rpcTimeout = options.rpcTimeout;
      this.rpcRetryOptions = options.rpcRetryOptions;
      this.connectionBackoffResetFrequency = options.connectionBackoffResetFrequency;
      this.grpcReconnectFrequency = options.grpcReconnectFrequency;
      this.headers = options.headers;
      this.grpcMetadataProviders = options.grpcMetadataProviders;
      this.grpcClientInterceptors = options.grpcClientInterceptors;
      this.metricsScope = options.metricsScope;
      this.disableHealthCheck = options.disableHealthCheck;
      this.healthCheckAttemptTimeout = options.healthCheckAttemptTimeout;
      this.healthCheckTimeout = options.healthCheckTimeout;
      this.enableKeepAlive = options.enableKeepAlive;
      this.keepAliveTime = options.keepAliveTime;
      this.keepAliveTimeout = options.keepAliveTimeout;
      this.keepAlivePermitWithoutStream = options.keepAlivePermitWithoutStream;
    }

    /**
     * Sets a target string, which can be either a valid {@link NameResolver}-compliant URI, or an
     * authority string. See {@link ManagedChannelBuilder#forTarget(String)} for more information
     * about parameter format. Default is {@link #DEFAULT_LOCAL_DOCKER_TARGET}
     *
     * <p>Mutually exclusive with {@link #setChannel(ManagedChannel)}.
     */
    public Builder setTarget(String target) {
      this.target = target;
      return this;
    }

    /**
     * Sets a listener to be called as a last step of channel creation if the channel is configured
     * by the {@link #setTarget(String)}. This listener can provide additional configuration to the
     * channel.
     *
     * <p>Mutually exclusive with {@link #setChannel(ManagedChannel)}.
     *
     * @param channelInitializer
     */
    public void setChannelInitializer(ChannelInitializer channelInitializer) {
      this.channelInitializer = channelInitializer;
    }

    /**
     * Sets fully custom user-configured gRPC channel to use.
     *
     * <p>Before supplying a fully custom channel using this method, it's recommended to first
     * consider using {@link #setTarget(String)} + other options of {@link
     * WorkflowServiceStubsOptions.Builder} + {@link #setChannelInitializer(ChannelInitializer)} for
     * some rarely used configuration.<br>
     * This option is not intended for the majority of users as it disables some Temporal connection
     * management features and can lead to outages if the channel is configured or managed
     * improperly.
     *
     * <p>Mutually exclusive with {@link #setTarget(String)}, {@link
     * #setChannelInitializer(ChannelInitializer)}, {@link #setSslContext(SslContext)}, {@link
     * #setGrpcReconnectFrequency(Duration)} and {@link
     * #setConnectionBackoffResetFrequency(Duration)}. These options are ignored if the custom
     * channel is supplied.
     */
    public Builder setChannel(ManagedChannel channel) {
      this.channel = channel;
      return this;
    }

    /**
     * Sets gRPC SSL Context to use, used for more advanced scenarios such as mTLS. Supersedes
     * enableHttps; Exclusive with channel. Consider using {@link SimpleSslContextBuilder} which
     * greatly simplifies creation of the TLS enabled SslContext with client and server key
     * validation.
     */
    public Builder setSslContext(SslContext sslContext) {
      this.sslContext = sslContext;
      return this;
    }

    /**
     * Sets option to enable SSL/TLS/HTTPS for gRPC.
     *
     * <p>Mutually exclusive with channel; Ignored if {@link #setSslContext(SslContext)} is
     * specified
     */
    public Builder setEnableHttps(boolean enableHttps) {
      this.enableHttps = enableHttps;
      return this;
    }

    /**
     * Sets the rpc timeout value for non-query and non-long-poll calls. Default is 10 seconds.
     *
     * <p>This timeout is applied to only a single rpc call within a temporal client, not a complete
     * client-server interaction. In case of failure, the requests are automatically retried
     * according to {@link #setRpcRetryOptions(RpcRetryOptions)}. The full timeout for an
     * interaction is limited by {@link RpcRetryOptions.Builder#setExpiration(Duration)} or {@link
     * RpcRetryOptions.Builder#setMaximumAttempts(int)}}, whichever happens first.
     *
     * <p><b>For example, let's consider you've called WorkflowClient#start, and this timeout is set
     * to 1s, while {@link RpcRetryOptions.Builder#setExpiration(Duration)} is set to 5s, and the
     * server is responding slowly. The first two RPC calls may time out and be retried, but if the
     * third one completes in &lt;1s, the overall call will successfully resolve.
     */
    public Builder setRpcTimeout(Duration timeout) {
      this.rpcTimeout = Objects.requireNonNull(timeout);
      return this;
    }

    /**
     * Sets the rpc timeout value for the following long poll based operations:
     * PollWorkflowTaskQueue, PollActivityTaskQueue, GetWorkflowExecutionHistory.
     *
     * <p>Server side timeout for the long poll is 60s. This parameter should never be below 70
     * seconds (server timeout + additional delay). Default is 70 seconds.
     *
     * @throws IllegalArgumentException if {@code timeout} is less than 70s
     * @deprecated exposing of this option for users configuration deemed non-beneficial and
     *     dangerous
     */
    @Deprecated
    public Builder setRpcLongPollTimeout(Duration timeout) {
      Preconditions.checkArgument(
          timeout.toMillis() > 70_000, "rpcLongPollTimeout has to be longer 70s");
      this.rpcLongPollTimeout = Objects.requireNonNull(timeout);
      return this;
    }

    /** Sets the rpc timeout for queries. Defaults to 10 seconds. */
    public Builder setRpcQueryTimeout(Duration rpcQueryTimeout) {
      this.rpcQueryTimeout = rpcQueryTimeout;
      return this;
    }

    /**
     * Allows customization of retry options for the outgoing RPC calls to temporal service. Note
     * that default values should be reasonable for most users, be cautious when changing these
     * values as it may result in increased load to the temporal backend or bad network instability
     * tolerance.
     *
     * @see #setRpcTimeout(Duration)
     */
    public Builder setRpcRetryOptions(RpcRetryOptions rpcRetryOptions) {
      this.rpcRetryOptions = rpcRetryOptions;
      return this;
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
     *     null in order to disable this feature.
     * @see ManagedChannel#resetConnectBackoff()
     */
    public Builder setConnectionBackoffResetFrequency(Duration connectionBackoffResetFrequency) {
      this.connectionBackoffResetFrequency = connectionBackoffResetFrequency;
      return this;
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
     *     order to disable this feature.
     * @see ManagedChannel#enterIdle()
     */
    public Builder setGrpcReconnectFrequency(Duration grpcReconnectFrequency) {
      this.grpcReconnectFrequency = grpcReconnectFrequency;
      return this;
    }

    /**
     * Sets the rpc timeout value for query calls. Default is 10 seconds.
     *
     * @param timeout timeout.
     */
    public Builder setQueryRpcTimeout(Duration timeout) {
      this.rpcQueryTimeout = Objects.requireNonNull(timeout);
      return this;
    }

    /**
     * @param headers gRPC headers to be added to every call.
     * @return {@code this}
     */
    public Builder setHeaders(Metadata headers) {
      this.headers = headers;
      return this;
    }

    /**
     * @param grpcMetadataProvider gRPC metadata/headers provider to be called on each gRPC request
     *     to supply additional headers
     * @return {@code this}
     */
    public Builder addGrpcMetadataProvider(GrpcMetadataProvider grpcMetadataProvider) {
      if (this.grpcMetadataProviders == null) {
        setGrpcMetadataProviders(new ArrayList<>());
      }
      this.grpcMetadataProviders.add(grpcMetadataProvider);
      return this;
    }

    /**
     * @param grpcMetadataProviders gRPC metadata/headers providers to be called on each gRPC
     *     request to supply additional headers.
     * @return {@code this}
     */
    public Builder setGrpcMetadataProviders(
        Collection<GrpcMetadataProvider> grpcMetadataProviders) {
      this.grpcMetadataProviders = grpcMetadataProviders;
      return this;
    }

    /**
     * @param grpcClientInterceptor gRPC client interceptor to be added to gRPC channel
     * @return {@code this}
     */
    public Builder addGrpcClientInterceptor(ClientInterceptor grpcClientInterceptor) {
      if (this.grpcClientInterceptors == null) {
        setGrpcClientInterceptors(new ArrayList<>());
      }
      this.grpcClientInterceptors.add(grpcClientInterceptor);
      return this;
    }

    /**
     * @param grpcClientInterceptors gRPC client interceptors to be added to gRPC channel
     * @return {@code this}
     */
    public Builder setGrpcClientInterceptors(Collection<ClientInterceptor> grpcClientInterceptors) {
      this.grpcClientInterceptors = grpcClientInterceptors;
      return this;
    }

    /**
     * Sets the scope to be used for metrics reporting. Optional. Default is to not report metrics.
     */
    public Builder setMetricsScope(Scope metricsScope) {
      this.metricsScope = metricsScope;
      return this;
    }

    /**
     * If false, enables client to make a request to health check endpoint to make sure that the
     * server is accessible.
     */
    public Builder setDisableHealthCheck(boolean disableHealthCheck) {
      this.disableHealthCheck = disableHealthCheck;
      return this;
    }

    /** Set the time to wait between service responses on each health check */
    public Builder setHealthCheckAttemptTimeout(Duration healthCheckAttemptTimeout) {
      this.healthCheckAttemptTimeout = healthCheckAttemptTimeout;
      return this;
    }

    /**
     * Set a HealthCheckTimeout after which to stop waiting while checking server connection when
     * creating new client.
     */
    public Builder setHealthCheckTimeout(Duration healthCheckTimeout) {
      this.healthCheckTimeout = healthCheckTimeout;
      return this;
    }

    /**
     * Enables keep alive ping from client to the server, which can help drop abruptly closed
     * connections faster.
     */
    public Builder setEnableKeepAlive(boolean enableKeepAlive) {
      this.enableKeepAlive = enableKeepAlive;
      return this;
    }

    /**
     * After a duration of this time if the client doesn't see any activity it pings the server to
     * see if the transport is still alive. If set below 10s, a minimum value of 10s will be used
     * instead.
     */
    public Builder setKeepAliveTime(Duration keepAliveTime) {
      this.keepAliveTime = keepAliveTime;
      return this;
    }

    /**
     * After having pinged for keepalive check, the client waits for a duration of Timeout and if no
     * activity is seen even after that the connection is closed.
     */
    public Builder setKeepAliveTimeout(Duration keepAliveTimeout) {
      this.keepAliveTimeout = keepAliveTimeout;
      return this;
    }

    /**
     * If true, client sends keepalive pings even with no active RPCs. If false, when there are no
     * active RPCs, Time and Timeout will be ignored and no keepalive pings will be sent.
     */
    public Builder setKeepAlivePermitWithoutStream(boolean keepAlivePermitWithoutStream) {
      this.keepAlivePermitWithoutStream = keepAlivePermitWithoutStream;
      return this;
    }

    /**
     * Builds and returns a ClientOptions object.
     *
     * @return ClientOptions object with the specified params.
     */
    public WorkflowServiceStubsOptions build() {
      return new WorkflowServiceStubsOptions(
          this.channel,
          this.target,
          this.channelInitializer,
          this.sslContext,
          this.enableHttps,
          this.disableHealthCheck,
          this.healthCheckAttemptTimeout,
          this.healthCheckTimeout,
          this.enableKeepAlive,
          this.keepAliveTime,
          this.keepAliveTimeout,
          this.keepAlivePermitWithoutStream,
          this.rpcTimeout,
          this.rpcLongPollTimeout,
          this.rpcQueryTimeout,
          this.rpcRetryOptions,
          this.connectionBackoffResetFrequency,
          this.grpcReconnectFrequency,
          this.headers,
          this.grpcMetadataProviders,
          this.grpcClientInterceptors,
          this.metricsScope);
    }

    public WorkflowServiceStubsOptions validateAndBuildWithDefaults() {
      if (this.target != null && this.channel != null) {
        throw new IllegalStateException(
            "Only one of the target and channel options can be set at a time");
      }

      if (this.channelInitializer != null && this.channel != null) {
        throw new IllegalStateException(
            "Only one of the channelInitializer and channel options can be set at a time");
      }

      if (this.sslContext != null && this.channel != null) {
        throw new IllegalStateException(
            "Only one of the sslContext and channel options can be set at a time");
      }

      if (this.enableHttps && this.channel != null) {
        throw new IllegalStateException(
            "Only one of the enableHttps and channel options can be set at a time");
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

      return new WorkflowServiceStubsOptions(
          this.channel,
          target,
          this.channelInitializer,
          this.sslContext,
          this.enableHttps,
          this.disableHealthCheck,
          healthCheckAttemptTimeout,
          healthCheckTimeout,
          this.enableKeepAlive,
          this.keepAliveTime,
          this.keepAliveTimeout,
          this.keepAlivePermitWithoutStream,
          this.rpcTimeout,
          this.rpcLongPollTimeout,
          this.rpcQueryTimeout,
          this.rpcRetryOptions,
          this.connectionBackoffResetFrequency,
          this.grpcReconnectFrequency,
          headers,
          grpcMetadataProviders,
          grpcClientInterceptors,
          metricsScope);
    }
  }

  /**
   * If the {@link WorkflowServiceStubsOptions} is configured with a {@code target} instead of
   * externally created {@code channel}, this listener is called as a last step of channel creation
   * giving an opportunity to provide some additional configuration to the channel.
   */
  public interface ChannelInitializer {
    void initChannel(ManagedChannelBuilder<?> managedChannelBuilder);
  }
}
