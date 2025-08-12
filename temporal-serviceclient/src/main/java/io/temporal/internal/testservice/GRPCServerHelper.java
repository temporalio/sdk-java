package io.temporal.internal.testservice;

import io.grpc.*;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

// TODO move to temporal-testing or temporal-test-server modules after WorkflowServiceStubs cleanup
public class GRPCServerHelper {
  public static void registerServicesAndHealthChecks(
      Collection<BindableService> services, ServerBuilder<?> toServerBuilder) {
    registerServicesAndHealthChecks(services, toServerBuilder, Collections.emptyList());
  }

  public static void registerServicesAndHealthChecks(
      Collection<BindableService> services,
      ServerBuilder<?> toServerBuilder,
      List<ServerInterceptor> interceptors) {
    HealthStatusManager healthStatusManager = new HealthStatusManager();
    for (BindableService service : services) {
      toServerBuilder.addService(ServerInterceptors.intercept(service.bindService(), interceptors));
      healthStatusManager.setStatus(
          service.bindService().getServiceDescriptor().getName(),
          HealthCheckResponse.ServingStatus.SERVING);
    }
    toServerBuilder.addService(
        ServerInterceptors.intercept(healthStatusManager.getHealthService(), interceptors));
  }
}
