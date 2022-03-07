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

import com.google.common.base.Preconditions;
import io.grpc.*;
import io.temporal.serviceclient.rpcretry.DefaultStubServiceOperationRpcRetryOptions;
import java.time.Duration;
import java.util.*;

public final class WorkflowServiceStubsOptions extends ServiceStubsOptions {
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

  private static final WorkflowServiceStubsOptions DEFAULT_INSTANCE =
      newBuilder().validateAndBuildWithDefaults();

  /**
   * Asks client to perform a health check after gRPC connection to the Server is created by making
   * a request to endpoint to make sure that the server is accessible.
   */
  private final boolean disableHealthCheck;

  /** The gRPC timeout for long poll calls */
  private final Duration rpcLongPollTimeout;

  /** The gRPC timeout for query workflow call */
  private final Duration rpcQueryTimeout;

  /** Retry options for outgoing RPC calls */
  private final RpcRetryOptions rpcRetryOptions;

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(WorkflowServiceStubsOptions options) {
    return new Builder(options);
  }

  public static Builder newBuilder(ServiceStubsOptions options) {
    return options instanceof WorkflowServiceStubsOptions
        ? newBuilder((WorkflowServiceStubsOptions) options)
        : new Builder(options);
  }

  public static WorkflowServiceStubsOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private WorkflowServiceStubsOptions(
      ServiceStubsOptions serviceStubsOptions,
      boolean disableHealthCheck,
      Duration rpcLongPollTimeout,
      Duration rpcQueryTimeout,
      RpcRetryOptions rpcRetryOptions) {
    super(serviceStubsOptions);
    this.disableHealthCheck = disableHealthCheck;
    this.rpcLongPollTimeout = rpcLongPollTimeout;
    this.rpcQueryTimeout = rpcQueryTimeout;
    this.rpcRetryOptions = rpcRetryOptions;
  }

  /**
   * @return false when client checks endpoint to make sure that the server is accessible.
   * @deprecated eager health check inService Stubs will be enforced and required in the next
   *     release.
   */
  @Deprecated
  public boolean getDisableHealthCheck() {
    return disableHealthCheck;
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

  // TODO remove this after removing of deprecated ChannelInitializer
  @Override
  public ChannelInitializer getChannelInitializer() {
    return channelInitializer instanceof ChannelInitializer
        ? (ChannelInitializer) channelInitializer
        : channelInitializer != null ? channelInitializer::initChannel : null;
  }

  /** Builder is the builder for ClientOptions. */
  public static class Builder extends ServiceStubsOptions.Builder<Builder> {
    private boolean disableHealthCheck;
    private Duration rpcLongPollTimeout = DEFAULT_POLL_RPC_TIMEOUT;
    private Duration rpcQueryTimeout = DEFAULT_QUERY_RPC_TIMEOUT;
    private RpcRetryOptions rpcRetryOptions = DefaultStubServiceOperationRpcRetryOptions.INSTANCE;

    private Builder() {}

    private Builder(ServiceStubsOptions options) {
      super(options);
    }

    private Builder(WorkflowServiceStubsOptions options) {
      super(options);
      this.rpcLongPollTimeout = options.rpcLongPollTimeout;
      this.rpcQueryTimeout = options.rpcQueryTimeout;
      this.rpcRetryOptions = options.rpcRetryOptions;
    }

    /**
     * If false, enables client to make a request to health check endpoint to make sure that the
     * server is accessible.
     *
     * @deprecated eager health check inService Stubs will be enforced and required in the next
     *     release.
     */
    @Deprecated
    public Builder setDisableHealthCheck(boolean disableHealthCheck) {
      this.disableHealthCheck = disableHealthCheck;
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
    @Override
    public Builder setRpcTimeout(Duration timeout) {
      return super.setRpcTimeout(timeout);
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
     * Sets the rpc timeout value for query calls. Default is 10 seconds.
     *
     * @param timeout timeout.
     * @deprecated use {{@link #setRpcQueryTimeout(Duration)}}
     */
    public Builder setQueryRpcTimeout(Duration timeout) {
      this.rpcQueryTimeout = Objects.requireNonNull(timeout);
      return this;
    }

    /**
     * Builds and returns a ClientOptions object.
     *
     * @return ClientOptions object with the specified params.
     */
    public WorkflowServiceStubsOptions build() {
      return new WorkflowServiceStubsOptions(
          super.build(),
          this.disableHealthCheck,
          this.rpcLongPollTimeout,
          this.rpcQueryTimeout,
          this.rpcRetryOptions);
    }

    public WorkflowServiceStubsOptions validateAndBuildWithDefaults() {
      ServiceStubsOptions serviceStubsOptions = super.validateAndBuildWithDefaults();
      return new WorkflowServiceStubsOptions(
          serviceStubsOptions,
          this.disableHealthCheck,
          this.rpcLongPollTimeout,
          this.rpcQueryTimeout,
          this.rpcRetryOptions);
    }
  }

  /**
   * If the {@link WorkflowServiceStubsOptions} is configured with a {@code target} instead of
   * externally created {@code channel}, this listener is called as a last step of channel creation
   * giving an opportunity to provide some additional configuration to the channel.
   *
   * @deprecated use {@link ServiceStubsOptions.ChannelInitializer}
   */
  @Deprecated
  public interface ChannelInitializer extends ServiceStubsOptions.ChannelInitializer {
    void initChannel(ManagedChannelBuilder<?> managedChannelBuilder);
  }
}
