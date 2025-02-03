package io.temporal.client;

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

class NamespaceInjectWorkflowServiceStubs implements WorkflowServiceStubs {
  private static Metadata.Key<String> TEMPORAL_NAMESPACE_HEADER_KEY =
      Metadata.Key.of("temporal-namespace", Metadata.ASCII_STRING_MARSHALLER);
  private final Metadata metadata;
  private WorkflowServiceStubs next;
  private String namespace;

  public NamespaceInjectWorkflowServiceStubs(WorkflowServiceStubs next, String namespace) {
    this.next = next;
    this.namespace = namespace;
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
            new GrpcMetadataProviderInterceptor(Collections.singleton(() -> metadata)));
  }

  @Override
  public WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub() {
    return next.futureStub()
        .withInterceptors(
            new GrpcMetadataProviderInterceptor(Collections.singleton(() -> metadata)));
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
