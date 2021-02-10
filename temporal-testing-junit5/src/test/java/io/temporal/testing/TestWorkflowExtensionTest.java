package io.temporal.testing;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;

public class TestWorkflowExtensionTest {

  @RegisterExtension
  public static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .setWorkflowTypes(HelloWorkflowImpl.class)
          .setActivityImplementations(new HelloActivityImpl())
          .build();

  @ActivityInterface
  public interface HelloActivity {
    String buildGreeting(String name);
  }

  public static class HelloActivityImpl implements HelloActivity {
    @Override
    public String buildGreeting(String name) {
      ActivityInfo activityInfo = Activity.getExecutionContext().getInfo();
      return String.format(
          "Hello %s from activity %s and workflow %s",
          name, activityInfo.getActivityType(), activityInfo.getWorkflowType());
    }
  }

  @WorkflowInterface
  public interface HelloWorkflow {
    @WorkflowMethod
    String sayHello(String name);
  }

  public static class HelloWorkflowImpl implements HelloWorkflow {

    private static final Logger logger = Workflow.getLogger(HelloWorkflowImpl.class);

    private final HelloActivity helloActivity =
        Workflow.newActivityStub(
            HelloActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(1)).build());

    @Override
    public String sayHello(String name) {
      logger.info("Hello, {}", name);
      Workflow.sleep(Duration.ofHours(1));
      return helloActivity.buildGreeting(name);
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void extensionShouldLaunchTestEnvironmentAndResolveParameters(
      TestWorkflowEnvironment testEnv,
      WorkflowClient workflowClient,
      WorkflowOptions workflowOptions,
      Worker worker,
      HelloWorkflow workflow) {

    assertAll(
        () -> assertTrue(testEnv.isStarted()),
        () -> assertNotNull(workflowClient),
        () -> assertNotNull(workflowOptions.getTaskQueue()),
        () -> assertNotNull(worker),
        () ->
            assertEquals(
                "Hello World from activity BuildGreeting and workflow HelloWorkflow",
                workflow.sayHello("World")));
  }
}
