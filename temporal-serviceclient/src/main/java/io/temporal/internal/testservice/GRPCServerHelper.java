package io.temporal.internal.testservice;

import io.grpc.BindableService;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;
import java.util.Collection;

// TODO move to temporal-testing or temporal-test-server modules after WorkflowServiceStubs cleanup
public class GRPCServerHelper {
  public static void registerServicesAndHealthChecks(
      Collection<BindableService> services, ServerBuilder<?> toServerBuilder) {
    HealthStatusManager healthStatusManager = new HealthStatusManager();
    for (BindableService service : services) {
      ServerServiceDefinition serverServiceDefinition = service.bindService();
      toServerBuilder.addService(serverServiceDefinition);
      healthStatusManager.setStatus(
          service.bindService().getServiceDescriptor().getName(),
          HealthCheckResponse.ServingStatus.SERVING);
    }
    toServerBuilder.addService(healthStatusManager.getHealthService());
  }
}
