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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc.HealthImplBase;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse.Capabilities;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceImplBase;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

public class ChannelManagerTest {

  private static final String HEALTH_CHECK_NAME = "my-health-check";

  private static final HealthCheckResponse HEALTH_CHECK_SERVING =
      HealthCheckResponse.newBuilder().setStatus(ServingStatus.SERVING).build();

  private static final Capabilities CAPABILITIES =
      Capabilities.newBuilder().setInternalErrorDifferentiation(true).build();

  private static final GetSystemInfoResponse GET_SYSTEM_INFO_RESPONSE =
      GetSystemInfoResponse.newBuilder().setCapabilities(CAPABILITIES).build();

  private static final RpcRetryOptions RPC_RETRY_OPTIONS =
      RpcRetryOptions.newBuilder()
          .setInitialInterval(Duration.ofMillis(10))
          .setBackoffCoefficient(1.0)
          .setMaximumAttempts(3)
          .setExpiration(Duration.ofMillis(100))
          .validateBuildWithDefaults();

  @Rule public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  private final AtomicInteger checkCount = new AtomicInteger(0);
  private final AtomicInteger checkUnavailable = new AtomicInteger(0);
  private final AtomicInteger getSystemInfoCount = new AtomicInteger(0);
  private final AtomicInteger getSystemInfoUnavailable = new AtomicInteger(0);
  private final AtomicInteger getSystemInfoUnimplemented = new AtomicInteger(0);

  private final HealthImplBase healthImpl =
      new HealthImplBase() {
        @Override
        public void check(
            HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
          if (!HEALTH_CHECK_NAME.equals(request.getService())) {
            responseObserver.onError(Status.fromCode(Status.Code.NOT_FOUND).asException());
          } else if (checkUnavailable.getAndDecrement() > 0) {
            responseObserver.onError(Status.fromCode(Status.Code.UNAVAILABLE).asException());
          } else {
            checkCount.getAndIncrement();
            responseObserver.onNext(HEALTH_CHECK_SERVING);
            responseObserver.onCompleted();
          }
        }
      };
  private final WorkflowServiceImplBase workflowImpl =
      new WorkflowServiceImplBase() {
        @Override
        public void getSystemInfo(
            GetSystemInfoRequest request, StreamObserver<GetSystemInfoResponse> responseObserver) {
          if (getSystemInfoUnavailable.getAndDecrement() > 0) {
            responseObserver.onError(Status.fromCode(Status.Code.UNAVAILABLE).asException());
          } else if (getSystemInfoUnimplemented.getAndDecrement() > 0) {
            responseObserver.onError(Status.fromCode(Status.Code.UNIMPLEMENTED).asException());
          } else {
            getSystemInfoCount.getAndIncrement();
            responseObserver.onNext(GET_SYSTEM_INFO_RESPONSE);
            responseObserver.onCompleted();
          }
        }
      };

  private ChannelManager channelManager;

  @Before
  public void setUp() throws Exception {
    checkCount.set(0);
    checkUnavailable.set(0);
    getSystemInfoCount.set(0);
    getSystemInfoUnavailable.set(0);
    getSystemInfoUnimplemented.set(0);
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanupRule.register(
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(healthImpl)
            .addService(workflowImpl)
            .build()
            .start());
    ManagedChannel channel =
        grpcCleanupRule.register(
            InProcessChannelBuilder.forName(serverName).directExecutor().build());
    WorkflowServiceStubsOptions serviceStubsOptions =
        WorkflowServiceStubsOptions.newBuilder()
            .setChannel(channel)
            .setRpcRetryOptions(RPC_RETRY_OPTIONS)
            .validateAndBuildWithDefaults();
    channelManager = new ChannelManager(serviceStubsOptions, Collections.emptyList());
  }

  @After
  public void tearDown() {
    if (channelManager != null) {
      channelManager.shutdownNow();
    }
  }

  @Test
  public void testGetServerCapabilities() {
    Capabilities capabilities = channelManager.getServerCapabilities().get();
    assertEquals(CAPABILITIES, capabilities);
    assertEquals(1, getSystemInfoCount.get());
    assertEquals(-1, getSystemInfoUnavailable.get());
    assertEquals(-1, getSystemInfoUnimplemented.get());
  }

  @Test
  public void testGetServerCapabilitiesRetry() {
    getSystemInfoUnavailable.set(2);
    Capabilities capabilities = channelManager.getServerCapabilities().get();
    assertEquals(CAPABILITIES, capabilities);
    assertEquals(1, getSystemInfoCount.get());
    assertEquals(-1, getSystemInfoUnavailable.get());
    assertEquals(-1, getSystemInfoUnimplemented.get());
  }

  @Test
  public void testGetServerCapabilitiesUnavailable() {
    getSystemInfoUnavailable.set(Integer.MAX_VALUE);
    try {
      Capabilities unused = channelManager.getServerCapabilities().get();
      Assert.fail("expected StatusRuntimeException");
    } catch (StatusRuntimeException e) {
      assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
      assertEquals(0, getSystemInfoCount.get());
      assertTrue(getSystemInfoUnavailable.get() >= 0);
      assertEquals(0, getSystemInfoUnimplemented.get());
    }
  }

  @Test
  public void testGetServerCapabilitiesUnimplemented() {
    getSystemInfoUnimplemented.set(1);
    Capabilities capabilities = channelManager.getServerCapabilities().get();
    assertEquals(Capabilities.getDefaultInstance(), capabilities);
    assertEquals(0, getSystemInfoCount.get());
    assertEquals(-1, getSystemInfoUnavailable.get());
    assertEquals(0, getSystemInfoUnimplemented.get());
  }

  @Test
  public void testGetServerCapabilitiesWithConnect() {
    channelManager.connect(HEALTH_CHECK_NAME, Duration.ofMillis(100));
    Capabilities capabilities = channelManager.getServerCapabilities().get();
    assertEquals(CAPABILITIES, capabilities);
    assertEquals(1, getSystemInfoCount.get());
    assertEquals(-1, getSystemInfoUnavailable.get());
    assertEquals(-1, getSystemInfoUnimplemented.get());
  }

  @Test
  public void testGetServerCapabilitiesRetryWithConnect() {
    getSystemInfoUnavailable.set(2);
    channelManager.connect(HEALTH_CHECK_NAME, Duration.ofMillis(100));
    Capabilities capabilities = channelManager.getServerCapabilities().get();
    assertEquals(CAPABILITIES, capabilities);
    assertEquals(1, getSystemInfoCount.get());
    assertEquals(-1, getSystemInfoUnavailable.get());
    assertEquals(-1, getSystemInfoUnimplemented.get());
  }

  @Test
  public void testGetServerCapabilitiesUnavailableWithConnect() {
    getSystemInfoUnavailable.set(Integer.MAX_VALUE);
    try {
      channelManager.connect(HEALTH_CHECK_NAME, Duration.ofMillis(100));
      Capabilities unused = channelManager.getServerCapabilities().get();
      Assert.fail("expected StatusRuntimeException");
    } catch (StatusRuntimeException e) {
      assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
      assertEquals(0, getSystemInfoCount.get());
      assertTrue(getSystemInfoUnavailable.get() >= 0);
      assertEquals(0, getSystemInfoUnimplemented.get());
    }
  }

  @Test
  public void testGetServerCapabilitiesUnimplementedWithConnect() {
    getSystemInfoUnimplemented.set(1);
    channelManager.connect(HEALTH_CHECK_NAME, Duration.ofMillis(100));
    Capabilities capabilities = channelManager.getServerCapabilities().get();
    assertEquals(Capabilities.getDefaultInstance(), capabilities);
    assertEquals(0, getSystemInfoCount.get());
    assertEquals(-1, getSystemInfoUnavailable.get());
    assertEquals(0, getSystemInfoUnimplemented.get());
  }
}
