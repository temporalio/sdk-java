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

import com.google.common.collect.ImmutableMap;
import io.temporal.proto.workflowservice.WorkflowServiceGrpc;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class WorkflowServiceStubsOptions {

  /** Default RPC timeout used for all non long poll calls. */
  private static final long DEFAULT_RPC_TIMEOUT_MILLIS = 1000;
  /** Default RPC timeout used for all long poll calls. */
  private static final long DEFAULT_POLL_RPC_TIMEOUT_MILLIS = 121 * 1000;
  /** Default RPC timeout for QueryWorkflow */
  private static final long DEFAULT_QUERY_RPC_TIMEOUT_MILLIS = 10000;

  private static final WorkflowServiceStubsOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = WorkflowServiceStubsOptions.newBuilder().build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static WorkflowServiceStubsOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  /** The tChannel timeout in milliseconds */
  private final long rpcTimeoutMillis;

  /** The tChannel timeout for long poll calls in milliseconds */
  private final long rpcLongPollTimeoutMillis;

  /** The tChannel timeout for query workflow call in milliseconds */
  private final long rpcQueryTimeoutMillis;

  /** Optional TChannel headers */
  private final Map<String, String> headers;

  private final Function<
          WorkflowServiceGrpc.WorkflowServiceBlockingStub,
          WorkflowServiceGrpc.WorkflowServiceBlockingStub>
      blockingStubInterceptor;

  private final Function<
          WorkflowServiceGrpc.WorkflowServiceFutureStub,
          WorkflowServiceGrpc.WorkflowServiceFutureStub>
      futureStubInterceptor;

  private WorkflowServiceStubsOptions(
      Builder builder,
      Function<
              WorkflowServiceGrpc.WorkflowServiceBlockingStub,
              WorkflowServiceGrpc.WorkflowServiceBlockingStub>
          blockingStubInterceptor,
      Function<
              WorkflowServiceGrpc.WorkflowServiceFutureStub,
              WorkflowServiceGrpc.WorkflowServiceFutureStub>
          futureStubInterceptor) {

    this.rpcLongPollTimeoutMillis = builder.rpcLongPollTimeoutMillis;
    this.rpcQueryTimeoutMillis = builder.rpcQueryTimeoutMillis;
    this.rpcTimeoutMillis = builder.rpcTimeoutMillis;
    this.blockingStubInterceptor = blockingStubInterceptor;
    this.futureStubInterceptor = futureStubInterceptor;

    if (builder.headers != null) {
      this.headers = ImmutableMap.copyOf(builder.headers);
    } else {
      this.headers = ImmutableMap.of();
    }
  }

  /** @return Returns the rpc timeout value in millis. */
  public long getRpcTimeoutMillis() {
    return rpcTimeoutMillis;
  }

  /** @return Returns the rpc timout for long poll requests in millis. */
  public long getRpcLongPollTimeoutMillis() {
    return rpcLongPollTimeoutMillis;
  }

  /** @return Returns the rpc timout for query workflow requests in millis. */
  public long getRpcQueryTimeoutMillis() {
    return rpcQueryTimeoutMillis;
  }

  public Map<String, String> getHeaders() {
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

  /**
   * Builder is the builder for ClientOptions.
   *
   * @author venkat
   */
  public static class Builder {
    private long rpcTimeoutMillis = DEFAULT_RPC_TIMEOUT_MILLIS;
    private long rpcLongPollTimeoutMillis = DEFAULT_POLL_RPC_TIMEOUT_MILLIS;
    public long rpcQueryTimeoutMillis = DEFAULT_QUERY_RPC_TIMEOUT_MILLIS;
    private Map<String, String> headers;
    private Function<
            WorkflowServiceGrpc.WorkflowServiceBlockingStub,
            WorkflowServiceGrpc.WorkflowServiceBlockingStub>
        blockingStubInterceptor;
    private Function<
            WorkflowServiceGrpc.WorkflowServiceFutureStub,
            WorkflowServiceGrpc.WorkflowServiceFutureStub>
        futureStubInterceptor;

    private Builder() {}

    /**
     * Sets the rpc timeout value for non query and non long poll calls. Default is 1000.
     *
     * @param timeoutMillis timeout, in millis.
     */
    public Builder setRpcTimeout(long timeoutMillis) {
      this.rpcTimeoutMillis = timeoutMillis;
      return this;
    }

    /**
     * Sets the rpc timeout value for the following long poll based operations: PollForDecisionTask,
     * PollForActivityTask, GetWorkflowExecutionHistory. Should never be below 60000 as this is
     * server side timeout for the long poll. Default is 61000.
     *
     * @param timeoutMillis timeout, in millis.
     */
    public Builder setRpcLongPollTimeout(long timeoutMillis) {
      this.rpcLongPollTimeoutMillis = timeoutMillis;
      return this;
    }

    /**
     * Sets the rpc timeout value for query calls. Default is 10000.
     *
     * @param timeoutMillis timeout, in millis.
     */
    public Builder setQueryRpcTimeout(long timeoutMillis) {
      this.rpcQueryTimeoutMillis = timeoutMillis;
      return this;
    }

    public Builder setHeaders(Map<String, String> headers) {
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
     * Builds and returns a ClientOptions object.
     *
     * @return ClientOptions object with the specified params.
     */
    public WorkflowServiceStubsOptions build() {
      return new WorkflowServiceStubsOptions(this, blockingStubInterceptor, futureStubInterceptor);
    }
  }
}
