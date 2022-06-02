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

package io.temporal.internal.testservice;

import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Generates an in-process service and the channel for it. Useful for testing, usually with mock and
 * spy services.
 */
// TODO move to temporal-testing or temporal-test-server modules after WorkflowServiceStubs cleanup
public class InProcessGRPCServer {
  private final Server server;
  @Nullable private final ManagedChannel channel;

  /**
   * Register the passed services in a new in-memory gRPC service with health service providing
   * SERVING status for all of them. Also provides a channel to access the created server, see
   * {@link #getChannel()}
   *
   * @param services implementations of the gRPC services. For example, one of them can be an
   *     instance extending {@link WorkflowServiceGrpc.WorkflowServiceImplBase}
   */
  public InProcessGRPCServer(Collection<BindableService> services) {
    this(services, true);
  }

  /**
   * Register the passed services in a new in-memory gRPC service with health service providing
   * SERVING status for all of them.
   *
   * @param services implementations of the gRPC services. For example, one of them can be an
   *     instance extending {@link WorkflowServiceGrpc.WorkflowServiceImplBase}
   * @param createChannel defines if a channel to access the created server should be created. If
   *     true, the created channel will be accessible using {@link #getChannel()}
   */
  public InProcessGRPCServer(Collection<BindableService> services, boolean createChannel) {
    String serverName = InProcessServerBuilder.generateName();
    try {
      InProcessServerBuilder inProcessServerBuilder = InProcessServerBuilder.forName(serverName);
      GRPCServerHelper.registerServicesAndHealthChecks(services, inProcessServerBuilder);
      server = inProcessServerBuilder.build().start();
    } catch (IOException unexpected) {
      throw new RuntimeException(unexpected);
    }
    channel =
        createChannel ? InProcessChannelBuilder.forName(serverName).directExecutor().build() : null;
  }

  public void shutdown() {
    if (channel != null) {
      channel.shutdown();
    }
    server.shutdown();
  }

  public void shutdownNow() {
    if (channel != null) {
      channel.shutdownNow();
    }
    server.shutdownNow();
  }

  public boolean isShutdown() {
    return (channel == null || channel.isShutdown()) && server.isShutdown();
  }

  public boolean isTerminated() {
    return (channel == null || channel.isTerminated()) && server.isTerminated();
  }

  public boolean awaitTermination(long timeout, TimeUnit unit) {
    long start = System.currentTimeMillis();
    long deadline = start + unit.toMillis(timeout);
    long left = deadline - System.currentTimeMillis();
    try {
      if (channel != null && !channel.awaitTermination(left, TimeUnit.MILLISECONDS)) {
        return false;
      }

      left = deadline - System.currentTimeMillis();
      return server.awaitTermination(left, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public Server getServer() {
    return server;
  }

  @Nullable
  public ManagedChannel getChannel() {
    return channel;
  }
}
