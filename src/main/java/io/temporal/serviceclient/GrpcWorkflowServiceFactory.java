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

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.temporal.WorkflowServiceGrpc;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@AutoValue
abstract class FactoryOptions {
  /** The timeout in milliseconds */
  abstract long rpcTimeoutMillis();
  /** The timeout for long poll calls in milliseconds */
  abstract long rpcLongPollTimeoutMillis();
  /** The timeout for query workflow call in milliseconds */
  abstract long rpcQueryTimeoutMillis();
  /** Optional headers */
  abstract Map<String, String> headers();

  static Builder builder() {
    return new AutoValue_FactoryOptions.Builder()
        .setRpcTimeoutMillis(1000)
        .setRpcLongPollTimeoutMillis(121 * 1000)
        .setRpcQueryTimeoutMillis(10000);
  }

  @AutoValue.Builder
  abstract static class Builder {
    abstract Builder setRpcTimeoutMillis(long value);

    abstract Builder setRpcLongPollTimeoutMillis(long value);

    abstract Builder setRpcQueryTimeoutMillis(long value);

    abstract Builder setHeaders(Map<String, String> value);

    abstract FactoryOptions build();
  }
}

/** TODO: (vkoby) Add metrics. */
public class GrpcWorkflowServiceFactory implements AutoCloseable {

  private static final String LOCALHOST = "127.0.0.1";
  private static final int DEFAULT_LOCAL_TEMPORAL_SERVER_PORT = 7933;

  private final FactoryOptions options;
  private final ManagedChannel channel;
  private final WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
  private final WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub;

  /**
   * Creates Temporal client that connects to the local instance of the Temporal Service that
   * listens on a default port (7933).
   */
  public GrpcWorkflowServiceFactory() {
    this(
        Strings.isNullOrEmpty(System.getenv("TEMPORAL_SEEDS"))
            ? LOCALHOST
            : System.getenv("TEMPORAL_SEEDS"),
        DEFAULT_LOCAL_TEMPORAL_SERVER_PORT);
  }

  public GrpcWorkflowServiceFactory(ManagedChannel channel, FactoryOptions options) {
    this.channel = channel;
    this.options = options;
    blockingStub = WorkflowServiceGrpc.newBlockingStub(channel);
    futureStub = WorkflowServiceGrpc.newFutureStub(channel);
  }

  public GrpcWorkflowServiceFactory(ManagedChannel channel) {
    this(channel, FactoryOptions.builder().build());
  }

  /**
   * Creates Temporal client that connects to the specified host and port using default options.
   *
   * @param host host to connect
   * @param port port to connect
   */
  public GrpcWorkflowServiceFactory(String host, int port) {
    this(host, port, FactoryOptions.builder().build());
  }

  /**
   * Creates Temporal client that connects to the specified host and port using specified options.
   *
   * @param host host to connect
   * @param port port to connect
   * @param options configuration options like rpc timeouts.
   */
  public GrpcWorkflowServiceFactory(String host, int port, FactoryOptions options) {
    this(
        ManagedChannelBuilder.forAddress(
                Preconditions.checkNotNull(host, "host must not be null"), validatePort(port))
            .usePlaintext()
            .build(),
        options);
  }

  /** @return Blocking (synchronous) stub that allows direct calls to service. */
  public WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub() {
    // TODO: If timeout is needed, add .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
    //  https://grpc.io/blog/deadlines/
    return blockingStub;
  }

  /** @return Future (asynchronous) stub that allows direct calls to service. */
  public WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub() {
    return futureStub;
  }

  /** Simple port validation */
  private static int validatePort(int port) {
    if (port < 0) {
      throw new IllegalArgumentException("0 or negative port");
    }
    return port;
  }
  /**
   * This closes the underlying channel. Channels are expensive to create, so if multiple instances
   * of this class are created and closed frequently, it may make sense to move to a shared channel
   * model. This implements the AutoCloseable interface, so this class can be used with
   * try-with-resources.
   */
  @Override
  public void close() {
    try {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException ignore) {
      /* Safe to ignore. */
    }
  }
}
