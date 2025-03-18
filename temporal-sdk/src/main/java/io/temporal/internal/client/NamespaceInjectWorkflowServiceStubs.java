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

package io.temporal.internal.client;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.health.v1.HealthCheckResponse;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.serviceclient.GrpcMetadataProviderInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Inject the namespace into the gRPC header, overriding the current namespace if already set. */
public class NamespaceInjectWorkflowServiceStubs implements WorkflowServiceStubs {
  private static Metadata.Key<String> TEMPORAL_NAMESPACE_HEADER_KEY =
      Metadata.Key.of("temporal-namespace", Metadata.ASCII_STRING_MARSHALLER);
  private final Metadata metadata;
  private final WorkflowServiceStubs next;

  public NamespaceInjectWorkflowServiceStubs(WorkflowServiceStubs next, String namespace) {
    this.next = next;
    this.metadata = new Metadata();
    metadata.put(TEMPORAL_NAMESPACE_HEADER_KEY, namespace);
  }

  @Override
  public WorkflowServiceStubsOptions getOptions() {
    return next.getOptions();
  }

  @Override
  public WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub() {
    return next.blockingStub()
        .withInterceptors(
            new GrpcMetadataProviderInterceptor(Collections.singleton(() -> metadata), true));
  }

  @Override
  public WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub() {
    return next.futureStub()
        .withInterceptors(
            new GrpcMetadataProviderInterceptor(Collections.singleton(() -> metadata), true));
  }

  @Override
  public ManagedChannel getRawChannel() {
    return next.getRawChannel();
  }

  @Override
  public void shutdown() {
    next.shutdown();
  }

  @Override
  public void shutdownNow() {
    next.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return next.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return next.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) {
    return next.awaitTermination(timeout, unit);
  }

  @Override
  public void connect(@Nullable Duration timeout) {
    next.connect(timeout);
  }

  @Override
  public HealthCheckResponse healthCheck() {
    return next.healthCheck();
  }

  @Override
  public Supplier<GetSystemInfoResponse.Capabilities> getServerCapabilities() {
    return next.getServerCapabilities();
  }
}
