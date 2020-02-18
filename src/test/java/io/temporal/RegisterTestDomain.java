package io.temporal;

import static io.temporal.workflow.WorkflowTest.DOMAIN;

import io.grpc.StatusRuntimeException;
import io.temporal.RequestResponse.RegisterDomainRequest;
import io.temporal.serviceclient.WorkflowServiceClient;

/** Waits for local service to become available and registers UnitTest domain. */
public class RegisterTestDomain {
  private static final boolean useDockerService =
      Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE"));

  public static void main(String[] args) throws InterruptedException {
    if (!useDockerService) {
      return;
    }

    WorkflowServiceClient service = new WorkflowServiceClient();
    RegisterDomainRequest request =
        RegisterDomainRequest.newBuilder()
            .setName(DOMAIN)
            .setWorkflowExecutionRetentionPeriodInDays(1)
            .build();
    while (true) {
      try {
        service.blockingStub().registerDomain(request);
        break;
        // TODO: Figure out exception handling.
        // TODO: GRPC gets StatusException or StatusRuntimeException with a status
        // TODO: That status needs to be looked at to determine the error.
      } catch (StatusRuntimeException e) {
        if (e.getTrailers().containsKey("?")) {
          // TODO: How is DomainAlreadyExistsError expressed? There is nothing similar in the proto.
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
