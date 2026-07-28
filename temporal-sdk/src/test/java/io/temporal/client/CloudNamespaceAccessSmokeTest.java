package io.temporal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class CloudNamespaceAccessSmokeTest {
  private static final String NAMESPACE = "sdk-ci.a2dd6";
  private static final String TARGET = "sdk-ci.a2dd6.tmprl.cloud:7233";

  @WorkflowInterface
  public interface SmokeWorkflow {
    @WorkflowMethod
    String run(String input);
  }

  @ActivityInterface
  public interface SmokeActivity {
    @ActivityMethod
    String echo(String input);
  }

  public static class SmokeWorkflowImpl implements SmokeWorkflow {
    private final SmokeActivity activity =
        Workflow.newActivityStub(
            SmokeActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public String run(String input) {
      return activity.echo(input);
    }
  }

  public static class SmokeActivityImpl implements SmokeActivity {
    @Override
    public String echo(String input) {
      return input;
    }
  }

  @Test
  public void apiKeyCanRunWorkflowAndActivity() throws InterruptedException {
    String apiKey = System.getenv("TEMPORAL_CLIENT_CLOUD_API_KEY");
    assertNotNull("TEMPORAL_CLIENT_CLOUD_API_KEY must be set", apiKey);

    String resourceName = "sdk-java-cloud-auth-smoke-" + UUID.randomUUID();
    WorkflowServiceStubs service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(TARGET)
                .setEnableHttps(true)
                .addApiKey(() -> apiKey)
                .build());
    WorkflowClient client =
        WorkflowClient.newInstance(
            service, WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).build());
    WorkerFactory factory = WorkerFactory.newInstance(client);

    try {
      Worker worker = factory.newWorker(resourceName);
      worker.registerWorkflowImplementationTypes(SmokeWorkflowImpl.class);
      worker.registerActivitiesImplementations(new SmokeActivityImpl());
      factory.start();

      SmokeWorkflow workflow =
          client.newWorkflowStub(
              SmokeWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setWorkflowId(resourceName)
                  .setTaskQueue(resourceName)
                  .setWorkflowExecutionTimeout(Duration.ofMinutes(2))
                  .build());

      assertEquals("smoke-ok", workflow.run("smoke-ok"));
    } finally {
      factory.shutdownNow();
      factory.awaitTermination(5, TimeUnit.SECONDS);
      service.shutdownNow();
      service.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
