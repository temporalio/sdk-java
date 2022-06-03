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

import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.health.v1.HealthCheckResponse;
import io.temporal.api.testservice.v1.TestServiceGrpc;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestServiceStubsImpl implements TestServiceStubs {
  private static final Logger log = LoggerFactory.getLogger(TestServiceStubsImpl.class);

  private final ChannelManager channelManager;

  private final TestServiceGrpc.TestServiceBlockingStub blockingStub;
  private final TestServiceGrpc.TestServiceFutureStub futureStub;

  /**
   * Creates gRPC Channel and Stubs that connects to the {@link TestServiceGrpc} according to the
   * specified options.
   */
  TestServiceStubsImpl(TestServiceStubsOptions options) {
    ClientInterceptor deadlineInterceptor =
        new GrpcDeadlineInterceptor(options.getRpcTimeout(), null, null);

    this.channelManager =
        new ChannelManager(options, Collections.singletonList(deadlineInterceptor));

    log.info("Created TestServiceStubs for channel: {}", channelManager.getRawChannel());

    this.blockingStub = TestServiceGrpc.newBlockingStub(channelManager.getInterceptedChannel());
    this.futureStub = TestServiceGrpc.newFutureStub(channelManager.getInterceptedChannel());
  }

  @Override
  public ManagedChannel getRawChannel() {
    return channelManager.getRawChannel();
  }

  @Override
  public TestServiceGrpc.TestServiceBlockingStub blockingStub() {
    return blockingStub;
  }

  @Override
  public TestServiceGrpc.TestServiceFutureStub futureStub() {
    return futureStub;
  }

  @Override
  public void shutdown() {
    log.info("shutdown");
    channelManager.shutdown();
  }

  @Override
  public void shutdownNow() {
    log.info("shutdownNow");
    channelManager.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return channelManager.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return channelManager.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) {
    return channelManager.awaitTermination(timeout, unit);
  }

  @Override
  public void connect(@Nullable Duration timeout) {
    channelManager.connect(HEALTH_CHECK_SERVICE_NAME, timeout);
  }

  @Override
  public HealthCheckResponse healthCheck() {
    return channelManager.healthCheck(HEALTH_CHECK_SERVICE_NAME, null);
  }
}
