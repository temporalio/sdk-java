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

import com.google.common.base.Strings;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.temporal.WorkflowServiceGrpc;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GRPCWorkflowServiceFactory implements AutoCloseable {
  private static final int DEFAULT_LOCAL_TEMPORAL_SERVER_PORT = 7933;
  private static final String LOCALHOST = "127.0.0.1";
  private static final long DEFAULT_RPC_TIMEOUT_MILLIS = 1000;
  private static final Logger log = LoggerFactory.getLogger(GRPCWorkflowServiceFactory.class);

  private final ManagedChannel channel;
  private final WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
  private final WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub;

  public GRPCWorkflowServiceFactory(ManagedChannel channel) {
    this.channel = channel;
    blockingStub = WorkflowServiceGrpc.newBlockingStub(channel);
    futureStub = WorkflowServiceGrpc.newFutureStub(channel);
  }

  public GRPCWorkflowServiceFactory(String host, int port) {
    // Remove usePlaintext if the service supports TLS
    this(ManagedChannelBuilder.forAddress(host, port).usePlaintext().build());
  }

  public GRPCWorkflowServiceFactory() {
    this(
        Strings.isNullOrEmpty(System.getenv("TEMPORAL_SEEDS"))
            ? LOCALHOST
            : System.getenv("TEMPORAL_SEEDS"),
        DEFAULT_LOCAL_TEMPORAL_SERVER_PORT);
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
