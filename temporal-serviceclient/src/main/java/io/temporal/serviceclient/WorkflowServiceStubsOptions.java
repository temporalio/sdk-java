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

import io.grpc.health.v1.HealthCheckResponse;
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

  public static final Duration DEFAULT_SYSTEM_INFO_TIMEOUT = Duration.ofSeconds(5);

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
  /** Timeout for the RPC made by the client to fetch server capabilities. */
  private final Duration systemInfoTimeout;

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ServiceStubsOptions options) {
    return new Builder(options);
  }

  public static WorkflowServiceStubsOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private WorkflowServiceStubsOptions(
      ServiceStubsOptions serviceStubsOptions,
      boolean disableHealthCheck,
      Duration rpcLongPollTimeout,
      Duration rpcQueryTimeout,
      Duration systemInfoTimeout,
      RpcRetryOptions rpcRetryOptions) {
    super(serviceStubsOptions);
    this.disableHealthCheck = disableHealthCheck;
    this.rpcLongPollTimeout = rpcLongPollTimeout;
    this.rpcQueryTimeout = rpcQueryTimeout;
    this.systemInfoTimeout = systemInfoTimeout;
    this.rpcRetryOptions = rpcRetryOptions;
  }

  /**
   * @return false when client checks endpoint to make sure that the server is accessible.
   * @deprecated ServiceStubs don't perform health check on start anymore to allow lazy
   *     connectivity. Users that prefer old behavior should explicitly call {@link
   *     ServiceStubs#healthCheck()} on client/stubs start and wait until it returns {@link
   *     HealthCheckResponse.ServingStatus#SERVING}
   */
  @Deprecated
  public boolean getDisableHealthCheck() {
    return disableHealthCheck;
  }

  /**
   * @return Returns the rpc timout for long poll requests.
   */
  public Duration getRpcLongPollTimeout() {
    return rpcLongPollTimeout;
  }

  /**
   * @return Returns the rpc timout for query workflow requests.
   */
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
   * SystemInfoTimeout is the timeout for the RPC made by the client to fetch server capabilities.
   */
  public Duration getSystemInfoTimeout() {
    return systemInfoTimeout;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowServiceStubsOptions that = (WorkflowServiceStubsOptions) o;
    return disableHealthCheck == that.disableHealthCheck
        && Objects.equals(rpcLongPollTimeout, that.rpcLongPollTimeout)
        && Objects.equals(rpcQueryTimeout, that.rpcQueryTimeout)
        && Objects.equals(systemInfoTimeout, that.systemInfoTimeout)
        && Objects.equals(rpcRetryOptions, that.rpcRetryOptions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        disableHealthCheck,
        rpcLongPollTimeout,
        rpcQueryTimeout,
        systemInfoTimeout,
        rpcRetryOptions);
  }

  @Override
  public String toString() {
    return "WorkflowServiceStubsOptions{"
        + "disableHealthCheck="
        + disableHealthCheck
        + ", rpcLongPollTimeout="
        + rpcLongPollTimeout
        + ", rpcQueryTimeout="
        + rpcQueryTimeout
        + ", systemInfoTimeout="
        + systemInfoTimeout
        + ", rpcRetryOptions="
        + rpcRetryOptions
        + '}';
  }

  /** Builder is the builder for ClientOptions. */
  public static class Builder extends ServiceStubsOptions.Builder<Builder> {
    private boolean disableHealthCheck = true;
    private Duration rpcLongPollTimeout = DEFAULT_POLL_RPC_TIMEOUT;
    private Duration rpcQueryTimeout = DEFAULT_QUERY_RPC_TIMEOUT;
    private Duration systemInfoTimeout = DEFAULT_SYSTEM_INFO_TIMEOUT;
    private RpcRetryOptions rpcRetryOptions = DefaultStubServiceOperationRpcRetryOptions.INSTANCE;

    private Builder() {}

    private Builder(ServiceStubsOptions options) {
      super(options);
      if (options instanceof WorkflowServiceStubsOptions) {
        WorkflowServiceStubsOptions castedOptions = (WorkflowServiceStubsOptions) options;
        this.rpcLongPollTimeout = castedOptions.rpcLongPollTimeout;
        this.rpcQueryTimeout = castedOptions.rpcQueryTimeout;
        this.systemInfoTimeout = castedOptions.systemInfoTimeout;
        this.rpcRetryOptions = castedOptions.rpcRetryOptions;
      }
    }

    /**
     * If false, enables client to make a request to health check endpoint to make sure that the
     * server is accessible.
     *
     * @deprecated Use more explicit {@link
     *     WorkflowServiceStubs#newServiceStubs(WorkflowServiceStubsOptions)} that doesn't perform
     *     an explicit connection and health check.
     */
    @Deprecated
    public Builder setDisableHealthCheck(boolean disableHealthCheck) {
      this.disableHealthCheck = disableHealthCheck;
      return this;
    }

    /**
     * Sets the rpc timeout value for non-query and non-long-poll calls. Default is 10 seconds.
     *
     * <p>This timeout is applied to only a single rpc server call, not a complete client-server
     * interaction. In case of failure, the requests are automatically retried according to {@link
     * #setRpcRetryOptions(RpcRetryOptions)}. The full interaction is limited by {@link
     * RpcRetryOptions.Builder#setExpiration(Duration)} or {@link
     * RpcRetryOptions.Builder#setMaximumAttempts(int)}}, whichever happens first.
     *
     * <p>For example, let's consider you've called {@code WorkflowClient#start}, and this timeout
     * is set to 10s, while {@link RpcRetryOptions.Builder#setExpiration(Duration)} is set to 60s,
     * and the server is responding slowly. The first two RPC calls may time out and be retried, but
     * if the third one completes fast, the overall {@code WorkflowClient#start} call will
     * successfully resolve.
     */
    @Override
    public Builder setRpcTimeout(Duration timeout) {
      return super.setRpcTimeout(timeout);
    }

    /**
     * Sets the rpc timeout value for the following long poll based operations:
     * PollWorkflowTaskQueue, PollActivityTaskQueue, GetWorkflowExecutionHistory. Defaults to 70
     * seconds.
     *
     * <p>Server always responds below this timeout. Most users should never modify the default
     * value of 70s. The only reasonable reason to modify this timeout it if there is a reversed
     * proxy in the network that cuts the gRPC requests really short and there is no way to adjust
     * it.
     */
    public Builder setRpcLongPollTimeout(Duration timeout) {
      this.rpcLongPollTimeout = Objects.requireNonNull(timeout);
      return this;
    }

    /**
     * Sets the rpc timeout value for query calls. Default is 10 seconds.
     *
     * <p>This timeout is applied to only a single rpc server call, not a complete client-server
     * interaction. In case of failure, the requests are automatically retried according to {@link
     * #setRpcRetryOptions(RpcRetryOptions)}. The full interaction is limited by {@link
     * RpcRetryOptions.Builder#setExpiration(Duration)} or {@link
     * RpcRetryOptions.Builder#setMaximumAttempts(int)}}, whichever happens first.
     *
     * <p>For example, let's consider you've called {@code WorkflowStub#query}, and this timeout is
     * set to 10s, while {@link RpcRetryOptions.Builder#setExpiration(Duration)} is set to 60s, and
     * the server is responding slowly or the query is not getting picked up by the worker for any
     * reason. The first two RPC calls may time out and be retried, but if the third one completes
     * fast, the overall {@code WorkflowStub#query} call will successfully resolve.
     */
    public Builder setRpcQueryTimeout(Duration rpcQueryTimeout) {
      this.rpcQueryTimeout = rpcQueryTimeout;
      return this;
    }

    /**
     * Sets the rpc timeout value RPC call to fetch server capabilities.
     *
     * @param timeout timeout.
     */
    public Builder setSystemInfoTimeout(Duration timeout) {
      this.systemInfoTimeout = Objects.requireNonNull(timeout);
      return this;
    }

    /**
     * Allows customization of retry options for the outgoing RPC calls to temporal service.
     *
     * <p>Note that default values should be reasonable for most users, be cautious when changing
     * these values as it may result in increased load to the temporal backend or bad network
     * instability tolerance.
     *
     * <p>Defaults are:
     *
     * <ul>
     *   <li>Retries are limited by the maximum period of 1 minute
     *   <li>Initial period between retries: 50ms
     *   <li>Exponential Backoff Coefficient (exponential rate) for the retry period is 2
     * </ul>
     *
     * @see <a href="http://backoffcalculator.com">Backoff Calculator</a> to get a grasp on an
     *     Exponential Backoff as a retry strategy
     * @see #setRpcTimeout(Duration)
     * @see #setRpcQueryTimeout(Duration)
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
          this.systemInfoTimeout,
          this.rpcRetryOptions);
    }

    public WorkflowServiceStubsOptions validateAndBuildWithDefaults() {
      ServiceStubsOptions serviceStubsOptions = super.validateAndBuildWithDefaults();
      RpcRetryOptions retryOptions =
          RpcRetryOptions.newBuilder(this.rpcRetryOptions).validateBuildWithDefaults();
      return new WorkflowServiceStubsOptions(
          serviceStubsOptions,
          this.disableHealthCheck,
          this.rpcLongPollTimeout,
          this.rpcQueryTimeout,
          this.systemInfoTimeout,
          retryOptions);
    }
  }
}
