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
import io.grpc.health.v1.HealthCheckResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.internal.testservice.InProcessGRPCServer;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WorkflowServiceStubsImpl implements WorkflowServiceStubs {
  private static final Logger log = LoggerFactory.getLogger(WorkflowServiceStubsImpl.class);

  private final WorkflowServiceStubsOptions options;
  private final InProcessGRPCServer inProcessServer;
  private final ChannelManager channelManager;

  private final WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
  private final WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub;

  /**
   * Creates a factory that connects to the {@link WorkflowServiceGrpc} according to the specified
   * options.
   *
   * @param serviceImpl If serviceImpl is not null generates the client for an in-process service
   *     using an in-memory channel. Useful for testing, usually with mock and spy services.
   */
  WorkflowServiceStubsImpl(BindableService serviceImpl, WorkflowServiceStubsOptions options) {
    Preconditions.checkArgument(
        !(serviceImpl != null && options.getChannel() != null),
        "both channel and serviceImpl present");

    if (serviceImpl != null) {
      // this code path is not currently used in JavaSDK,
      // inProcessServer initialization is moved to TestWorkflowEnvironmentInternal
      inProcessServer = new InProcessGRPCServer(Collections.singletonList(serviceImpl));
      options =
          WorkflowServiceStubsOptions.newBuilder(options)
              .setChannel(inProcessServer.getChannel())
              // target might be set already, especially if options were built with defaults. Need
              // to set it to null since we don't allow both channel and target be set at the same
              // time.
              .setTarget(null)
              .build();
    } else {
      inProcessServer = null;
    }
    this.options = WorkflowServiceStubsOptions.newBuilder(options).validateAndBuildWithDefaults();
    // if the line above is ever gone, this line still needs to stay to validate an input
    // rpcRetryOptions
    this.options.getRpcRetryOptions().validate();

    ClientInterceptor deadlineInterceptor =
        new GrpcDeadlineInterceptor(
            options.getRpcTimeout(), options.getRpcLongPollTimeout(), options.getRpcQueryTimeout());

    this.channelManager =
        new ChannelManager(this.options, Collections.singletonList(deadlineInterceptor));

    log.info(
        String.format(
            "Created WorkflowServiceStubs for channel: %s", channelManager.getRawChannel()));

    this.blockingStub = WorkflowServiceGrpc.newBlockingStub(channelManager.getInterceptedChannel());
    this.futureStub = WorkflowServiceGrpc.newFutureStub(channelManager.getInterceptedChannel());
  }

  @Override
  public WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub() {
    return blockingStub;
  }

  @Override
  public WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub() {
    return futureStub;
  }

  @Override
  public ManagedChannel getRawChannel() {
    return channelManager.getRawChannel();
  }

  @Override
  public void shutdown() {
    log.info("shutdown");
    channelManager.shutdown();
    if (inProcessServer != null) {
      inProcessServer.shutdown();
    }
  }

  @Override
  public void shutdownNow() {
    log.info("shutdownNow");
    channelManager.shutdownNow();
    if (inProcessServer != null) {
      inProcessServer.shutdownNow();
    }
  }

  @Override
  public boolean isShutdown() {
    boolean result = channelManager.isShutdown();
    if (inProcessServer != null) {
      result = result && inProcessServer.isShutdown();
    }
    return result;
  }

  @Override
  public boolean isTerminated() {
    boolean result = channelManager.isTerminated();
    if (inProcessServer != null) {
      result = result && inProcessServer.isTerminated();
    }
    return result;
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) {
    long start = System.currentTimeMillis();
    long deadline = start + unit.toMillis(timeout);
    if (!channelManager.awaitTermination(timeout, unit)) {
      return false;
    }
    if (inProcessServer != null) {
      long left = deadline - System.currentTimeMillis();
      return inProcessServer.awaitTermination(left, TimeUnit.MILLISECONDS);
    }
    return true;
  }

  @Override
  public void connect(@Nullable Duration timeout) {
    channelManager.connect(HEALTH_CHECK_SERVICE_NAME, timeout);
  }

  @Override
  public HealthCheckResponse healthCheck() {
    // no need to pass timeout, timeout will be assigned by GrpcDeadlineInterceptor
    return channelManager.healthCheck(HEALTH_CHECK_SERVICE_NAME, null);
  }

  @Override
  public WorkflowServiceStubsOptions getOptions() {
    return options;
  }
}
