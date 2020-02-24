package io.temporal;

import static io.temporal.workflow.WorkflowTest.DOMAIN;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.serviceclient.GrpcFailure;
import io.temporal.serviceclient.GrpcStatusUtils;
import io.temporal.serviceclient.GrpcWorkflowServiceFactory;

/** Waits for local service to become available and registers UnitTest domain. */
public class RegisterTestDomain {
  private static final boolean useDockerService =
      Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE"));

  public static void main(String[] args) throws InterruptedException {
    if (!useDockerService) {
      return;
    }

    GrpcWorkflowServiceFactory service = new GrpcWorkflowServiceFactory();
    RegisterDomainRequest request =
        RegisterDomainRequest.newBuilder()
            .setName(DOMAIN)
            .setWorkflowExecutionRetentionPeriodInDays(1)
            .build();
    while (true) {
      try {
        service.blockingStub().registerDomain(request);
        break;
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode().equals(Status.Code.ALREADY_EXISTS)
            && GrpcStatusUtils.hasFailure(e, GrpcFailure.DOMAIN_ALREADY_EXISTS_FAILURE)) {
          break;
        }
      } catch (RuntimeException e) {
        String message = e.getMessage();
        if (message != null
            && !message.contains("Failed to connect to the host")
            && !message.contains("Connection timeout on identification")) {
          e.printStackTrace();
        }
        Thread.sleep(500);
        continue;
      } catch (Throwable e) {
        e.printStackTrace();
        System.exit(1);
      }
    }
    System.exit(0);
  }
}
