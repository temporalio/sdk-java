package io.temporal.internal.docker;

import com.google.protobuf.util.Durations;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.DescribeNamespaceResponse;
import io.temporal.api.workflowservice.v1.ListNamespacesRequest;
import io.temporal.api.workflowservice.v1.ListNamespacesResponse;
import io.temporal.api.workflowservice.v1.RegisterNamespaceRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

/** Waits for local service to become available and registers UnitTest namespace. */
public class RegisterTestNamespace {
  public static final String NAMESPACE = "UnitTest";
  private static final boolean useDockerService =
      Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE"));
  private static final String serviceAddress = System.getenv("TEMPORAL_SERVICE_ADDRESS");

  public static void main(String[] args) throws InterruptedException {
    if (!useDockerService) {
      return;
    }

    WorkflowServiceStubsOptions.Builder options = WorkflowServiceStubsOptions.newBuilder();
    if (serviceAddress != null) {
      options.setTarget(serviceAddress);
    }

    WorkflowServiceStubs service = null;
    try {
      service = WorkflowServiceStubs.newServiceStubs(options.build());
      if (doesNamespaceExist(service)) {
        System.out.println("Namespace " + NAMESPACE + " already exists");
      } else {
        registerNamespace(service);
        waitForNamespace(service);
      }
      System.exit(0);
    } finally {
      if (service != null) {
        service.shutdown();
      }
    }
    System.exit(0);
  }

  private static void registerNamespace(WorkflowServiceStubs service) throws InterruptedException {
    RegisterNamespaceRequest request =
        RegisterNamespaceRequest.newBuilder()
            .setNamespace(NAMESPACE)
            .setWorkflowExecutionRetentionPeriod(Durations.fromDays(1))
            .build();
    while (true) {
      try {
        service.blockingStub().registerNamespace(request);
        System.out.println("Namespace " + NAMESPACE + " registered");
        break;
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
          System.out.println("Namespace " + NAMESPACE + " already exists");
          break;
        }
        if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED
            || e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
          e.printStackTrace();
          Thread.sleep(500);
        }
      } catch (Throwable e) {
        e.printStackTrace();
        System.exit(1);
      }
    }
  }

  private static void waitForNamespace(WorkflowServiceStubs service) throws InterruptedException {
    // We have a namespace cache on the server side which is refreshed every 10seconds. All
    // namespace centric APIs
    // don't use the cache, so the best way is just to wait for it to expire. Another way is to call
    // some
    // workflow/activity tasks poll APIs and wait till they stop failing
    // https://github.com/temporalio/temporal/issues/1941
    System.out.println("Waiting for the namespace propagation...");
    Thread.sleep(10000);
  }

  private static boolean doesNamespaceExist(WorkflowServiceStubs service) {
    ListNamespacesRequest request = ListNamespacesRequest.newBuilder().build();
    ListNamespacesResponse listNamespacesResponse = service.blockingStub().listNamespaces(request);
    for (DescribeNamespaceResponse namespaceResponse : listNamespacesResponse.getNamespacesList()) {
      if (namespaceResponse.getNamespaceInfo().getName().equals(NAMESPACE)) {
        return true;
      }
    }
    return false;
  }
}
