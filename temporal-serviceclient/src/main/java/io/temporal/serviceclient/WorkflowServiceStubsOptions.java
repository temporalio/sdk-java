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

import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.NameResolver;
import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolConfig;
import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolNames;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.FingerprintTrustManagerFactory;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.net.ssl.SSLException;

public class WorkflowServiceStubsOptions {

  private static final String LOCAL_DOCKER_TARGET = "127.0.0.1:7233";

  /** Default RPC timeout used for all non long poll calls. */
  private static final Duration DEFAULT_RPC_TIMEOUT = Duration.ofSeconds(10);
  /** Default RPC timeout used for all long poll calls. */
  private static final Duration DEFAULT_POLL_RPC_TIMEOUT = Duration.ofSeconds(121);
  /** Default RPC timeout for QueryWorkflow */
  private static final Duration DEFAULT_QUERY_RPC_TIMEOUT = Duration.ofSeconds(10);
  /** Default timeout that will be used to reset connection backoff. */
  private static final Duration DEFAULT_CONNECTION_BACKOFF_RESET_FREQUENCY = Duration.ofSeconds(10);

  private static final WorkflowServiceStubsOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = WorkflowServiceStubsOptions.newBuilder().build();
  }

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

  private final String target;

  /** The user provided context for SSL/TLS over gRPC * */
  private final SslContext sslContext;

  /** Indicates whether basic HTTPS/SSL/TLS should be enabled * */
  private final boolean enableHttps;

  /** The gRPC timeout */
  private final Duration rpcTimeout;

  /** The gRPC timeout for long poll calls */
  private final Duration rpcLongPollTimeout;

  /** The gRPC timeout for query workflow call */
  private final Duration rpcQueryTimeout;

  /** Frequency at which connection backoff is going to be reset */
  private final Duration connectionBackoffResetFrequency;

  /** Optional gRPC headers */
  private final Metadata headers;

  private final Scope metricsScope;

  private final Function<
          WorkflowServiceGrpc.WorkflowServiceBlockingStub,
          WorkflowServiceGrpc.WorkflowServiceBlockingStub>
      blockingStubInterceptor;

  private final Function<
          WorkflowServiceGrpc.WorkflowServiceFutureStub,
          WorkflowServiceGrpc.WorkflowServiceFutureStub>
      futureStubInterceptor;

  private WorkflowServiceStubsOptions(Builder builder) {
    this.target = builder.target;
    this.sslContext = builder.sslContext;
    this.enableHttps = builder.enableHttps;
    this.channel = builder.channel;
    this.rpcLongPollTimeout = builder.rpcLongPollTimeout;
    this.rpcQueryTimeout = builder.rpcQueryTimeout;
    this.rpcTimeout = builder.rpcTimeout;
    this.connectionBackoffResetFrequency = builder.connectionBackoffResetFrequency;
    this.blockingStubInterceptor = builder.blockingStubInterceptor;
    this.futureStubInterceptor = builder.futureStubInterceptor;
    this.headers = builder.headers;
    this.metricsScope = builder.metricsScope;
  }

  private WorkflowServiceStubsOptions(Builder builder, boolean ignore) {
    if (builder.target != null && builder.channel != null) {
      throw new IllegalStateException(
          "Only one of the target and channel options can be set at a time");
    }

    if (builder.sslContext != null && builder.channel != null) {
      throw new IllegalStateException(
          "Only one of the sslContext and channel options can be set at a time");
    }

    if (builder.enableHttps && builder.channel != null) {
      throw new IllegalStateException(
          "Only one of the enableHttps and channel options can be set at a time");
    }

    this.target =
        builder.target == null && builder.channel == null ? LOCAL_DOCKER_TARGET : builder.target;
    this.sslContext = builder.sslContext;
    this.enableHttps = builder.enableHttps;
    this.channel = builder.channel;
    this.rpcLongPollTimeout = builder.rpcLongPollTimeout;
    this.rpcQueryTimeout = builder.rpcQueryTimeout;
    this.rpcTimeout = builder.rpcTimeout;
    this.connectionBackoffResetFrequency = builder.connectionBackoffResetFrequency;
    this.blockingStubInterceptor = builder.blockingStubInterceptor;
    this.futureStubInterceptor = builder.futureStubInterceptor;
    if (builder.headers != null) {
      this.headers = builder.headers;
    } else {
      this.headers = new Metadata();
    }
    this.metricsScope = builder.metricsScope == null ? new NoopScope() : builder.metricsScope;
  }

  public ManagedChannel getChannel() {
    return channel;
  }

  public String getTarget() {
    return target;
  }

  /** @return Returns the gRPC SSL Context to use. * */
  public SslContext getSslContext() {
    return sslContext;
  }

  /** @return Returns a boolean indicating whether gRPC should use SSL/TLS. * */
  public boolean getEnableHttps() {
    return enableHttps;
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
   * @return frequency at which connection backoff should be reset or null if backoff reset is
   *     disabled.
   */
  public Duration getConnectionBackoffResetFrequency() {
    return connectionBackoffResetFrequency;
  }

  public Metadata getHeaders() {
    return headers;
  }

  public Optional<
          Function<
              WorkflowServiceGrpc.WorkflowServiceBlockingStub,
              WorkflowServiceGrpc.WorkflowServiceBlockingStub>>
      getBlockingStubInterceptor() {
    return Optional.ofNullable(blockingStubInterceptor);
  }

  public Optional<
          Function<
              WorkflowServiceGrpc.WorkflowServiceFutureStub,
              WorkflowServiceGrpc.WorkflowServiceFutureStub>>
      getFutureStubInterceptor() {
    return Optional.ofNullable(futureStubInterceptor);
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

    // Default TLS protocol config that is used to communicate with temporal backend in the cloud.
    private static final ApplicationProtocolConfig DEFAULT_APPLICATION_PROTOCOL_CONFIG =
        new ApplicationProtocolConfig(
            // HTTP/2 over TLS mandates the use of ALPN to negotiate the use of the protocol.
            ApplicationProtocolConfig.Protocol.ALPN,
            // NO_ADVERTISE is the only mode supported by both OpenSsl and JDK providers.
            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
            // ACCEPT is the only mode supported by both OpenSsl and JDK providers.
            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
            // gRPC requires http2 protocol.
            ApplicationProtocolNames.HTTP_2);
    private ManagedChannel channel;
    private SslContext sslContext;
    private boolean enableHttps;
    private String target;
    private Duration rpcTimeout = DEFAULT_RPC_TIMEOUT;
    private Duration rpcLongPollTimeout = DEFAULT_POLL_RPC_TIMEOUT;
    private Duration rpcQueryTimeout = DEFAULT_QUERY_RPC_TIMEOUT;
    private Duration connectionBackoffResetFrequency = DEFAULT_CONNECTION_BACKOFF_RESET_FREQUENCY;
    private Metadata headers;
    private Function<
            WorkflowServiceGrpc.WorkflowServiceBlockingStub,
            WorkflowServiceGrpc.WorkflowServiceBlockingStub>
        blockingStubInterceptor;
    private Function<
            WorkflowServiceGrpc.WorkflowServiceFutureStub,
            WorkflowServiceGrpc.WorkflowServiceFutureStub>
        futureStubInterceptor;
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
      this.connectionBackoffResetFrequency = options.connectionBackoffResetFrequency;
      this.blockingStubInterceptor = options.blockingStubInterceptor;
      this.futureStubInterceptor = options.futureStubInterceptor;
      this.headers = options.headers;
      this.metricsScope = options.metricsScope;
    }

    /** Sets gRPC channel to use. Exclusive with target and sslContext. */
    public Builder setChannel(ManagedChannel channel) {
      this.channel = channel;
      return this;
    }

    /**
     * Sets gRPC SSL Context to use, used for more advanced scenarios such as mTLS. Supercedes
     * enableHttps; Exclusive with channel.
     */
    public Builder setSslContext(SslContext sslContext) {
      this.sslContext = sslContext;
      return this;
    }

    /**
     * Sets default SSL context parameters that can be used with the temporal cloud service. Users
     * that require additional customization may use {@link #setSslContext(SslContext)} directly.
     *
     * @param keyCertChainInputStream - an input stream for an X.509 certificate chain in PEM
     *     format.
     * @param keyPassword - the password of the keyFile, or null if it's not password-protected.
     * @param keyInputStream - an input stream for a PKCS#8 private key in PEM format.
     * @param fingerprints - a list of SHA1 fingerprints in hexadecimal form.
     * @throws SSLException - when it was unable to build the context.
     */
    public Builder setCloudSslContext(
        InputStream keyCertChainInputStream,
        String keyPassword,
        InputStream keyInputStream,
        String... fingerprints)
        throws SSLException {
      // TODO consider using default fingerprints from the root CA if user didn't pass any.
      this.sslContext =
          SslContextBuilder.forClient()
              .trustManager(new FingerprintTrustManagerFactory(fingerprints))
              .keyManager(keyCertChainInputStream, keyInputStream, keyPassword)
              .applicationProtocolConfig(DEFAULT_APPLICATION_PROTOCOL_CONFIG)
              .build();
      return this;
    }

    /**
     * Convenience method that overloads {@link #setCloudSslContext(InputStream, String,
     * InputStream, String...)} and uses no key password.
     *
     * @param keyCertChainInputStream - an input stream for an X.509 certificate chain in PEM
     *     format.
     * @param keyInputStream - an input stream for a PKCS#8 private key in PEM format.
     * @param fingerprints - a list of SHA1 fingerprints in hexadecimal form.
     */
    public Builder setCloudSslContext(
        InputStream keyCertChainInputStream, InputStream keyInputStream, String... fingerprints)
        throws IOException {
      return setCloudSslContext(keyCertChainInputStream, null, keyInputStream, fingerprints);
    }

    /**
     * Sets default SSL context parameters that can be used with the temporal cloud service. Users
     * that require additional customization may use {@link #setSslContext(SslContext)} directly.
     *
     * @param keyCertChainFile - an X.509 certificate chain file in PEM format.
     * @param keyPassword - the password of the keyFile, or null if it's not password-protected.
     * @param keyFile - a PKCS#8 private key file in PEM format.
     * @param fingerprints - a list of SHA1 fingerprints in hexadecimal form.
     */
    public Builder setCloudSslContext(
        File keyCertChainFile, String keyPassword, File keyFile, String... fingerprints)
        throws IOException {
      // TODO consider using default fingerprints from the root CA if user didn't pass any.
      this.sslContext =
          SslContextBuilder.forClient()
              .trustManager(new FingerprintTrustManagerFactory(fingerprints))
              .keyManager(keyCertChainFile, keyFile, keyPassword)
              .applicationProtocolConfig(DEFAULT_APPLICATION_PROTOCOL_CONFIG)
              .build();
      return this;
    }

    /**
     * Convenience method that overloads {@link #setCloudSslContext(File, String, File, String...)}
     * and uses no key password.
     *
     * @param keyCertChainFile - an X.509 certificate chain file in PEM format.
     * @param keyFile - a PKCS#8 private key file in PEM format.
     * @param fingerprints - a list of SHA1 fingerprints in hexadecimal form.
     */
    public Builder setCloudSslContext(File keyCertChainFile, File keyFile, String... fingerprints)
        throws IOException {
      return setCloudSslContext(keyCertChainFile, null, keyFile, fingerprints);
    }

    /**
     * Sets option to enable SSL/TLS/HTTPS for gRPC. Exclusive with channel; Ignored if SSLContext
     * is specified
     */
    public Builder setEnableHttps(boolean enableHttps) {
      this.enableHttps = enableHttps;
      return this;
    }

    /**
     * Sets a target string, which can be either a valid {@link NameResolver}-compliant URI, or an
     * authority string. See {@link ManagedChannelBuilder#forTarget(String)} for more information
     * about parameter format.
     *
     * <p>Exclusive with channel.
     */
    public Builder setTarget(String target) {
      this.target = target;
      return this;
    }

    /** Sets the rpc timeout value for non query and non long poll calls. Default is 1000. */
    public Builder setRpcTimeout(Duration timeout) {
      this.rpcTimeout = Objects.requireNonNull(timeout);
      return this;
    }

    /**
     * Sets the rpc timeout value for the following long poll based operations:
     * PollWorkflowTaskQueue, PollActivityTaskQueue, GetWorkflowExecutionHistory. Should never be
     * below 60000 as this is server side timeout for the long poll. Default is 61000.
     */
    public Builder setRpcLongPollTimeout(Duration timeout) {
      this.rpcLongPollTimeout = Objects.requireNonNull(timeout);
      return this;
    }

    /**
     * Sets frequency at which gRPC connection backoff should be reset practically defining an upper
     * limit for the maximum backoff duration. If set to null then no backoff reset will be
     * performed and we'll rely on default gRPC backoff behavior defined in
     * ExponentialBackoffPolicy.
     *
     * @param connectionBackoffResetFrequency frequency.
     */
    public Builder setConnectionBackoffResetFrequency(Duration connectionBackoffResetFrequency) {
      this.connectionBackoffResetFrequency = connectionBackoffResetFrequency;
      return this;
    }

    /**
     * Sets the rpc timeout value for query calls. Default is 10000.
     *
     * @param timeout timeout.
     */
    public Builder setQueryRpcTimeout(Duration timeout) {
      this.rpcQueryTimeout = Objects.requireNonNull(timeout);
      return this;
    }

    public Builder setHeaders(Metadata headers) {
      this.headers = headers;
      return this;
    }

    public Builder setBlockingStubInterceptor(
        Function<
                WorkflowServiceGrpc.WorkflowServiceBlockingStub,
                WorkflowServiceGrpc.WorkflowServiceBlockingStub>
            blockingStubInterceptor) {
      this.blockingStubInterceptor = blockingStubInterceptor;
      return this;
    }

    public Builder setFutureStubInterceptor(
        Function<
                WorkflowServiceGrpc.WorkflowServiceFutureStub,
                WorkflowServiceGrpc.WorkflowServiceFutureStub>
            futureStubInterceptor) {
      this.futureStubInterceptor = futureStubInterceptor;
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
     * Builds and returns a ClientOptions object.
     *
     * @return ClientOptions object with the specified params.
     */
    public WorkflowServiceStubsOptions build() {
      return new WorkflowServiceStubsOptions(this);
    }

    public WorkflowServiceStubsOptions validateAndBuildWithDefaults() {
      return new WorkflowServiceStubsOptions(this, true);
    }
  }
}
