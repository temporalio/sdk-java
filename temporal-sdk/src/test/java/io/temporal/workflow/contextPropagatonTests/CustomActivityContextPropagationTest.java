package io.temporal.workflow.contextPropagatonTests;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.MDC;

public class CustomActivityContextPropagationTest {
  public static final String CUSTOM_KEY = "custom_key";

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          System.err.println(testEnvironment.getDiagnostics());
        }
      };

  private static final String TASK_QUEUE = "test-workflow";

  private TestWorkflowEnvironment testEnvironment;

  @Before
  public void setUp() {
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setContextPropagators(
                        Arrays.asList(
                            new TestContextPropagator(),
                            // This has to be on worker config. Propagators applied on
                            // ActivityOptions are not propagated automatically to the Activity Worker side.
                            new TestContextPropagator(CUSTOM_KEY)))
                    .build())
            .build();
    testEnvironment = TestWorkflowEnvironment.newInstance(options);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow1(String input);
  }

  @ActivityInterface
  public interface TestActivity {
    String activity(String input);
  }

  public static class ContextActivityImpl implements TestActivity {
    @Override
    public String activity(String input) {
      return "activity_" + MDC.get(TestContextPropagator.DEFAULT_KEY) + "_" + MDC.get(CUSTOM_KEY);
    }
  }

  public static class ContextPropagationActivityWorkflowImpl implements TestWorkflow {
    @Override
    public String workflow1(String input) {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .setContextPropagators(
                  Collections.singletonList(new TestContextPropagator(CUSTOM_KEY)))
              .build();
      TestActivity activity = Workflow.newActivityStub(TestActivity.class, options);
      MDC.put(CUSTOM_KEY, "testing1234");

      return activity.activity("foo");
    }
  }

  public static class ContextPropagationLocalActivityWorkflowImpl implements TestWorkflow {
    @Override
    public String workflow1(String input) {
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .setContextPropagators(
                  Collections.singletonList(new TestContextPropagator(CUSTOM_KEY)))
              .build();
      TestActivity activity = Workflow.newLocalActivityStub(TestActivity.class, options);
      MDC.put(CUSTOM_KEY, "testing1234");

      return activity.activity("foo");
    }
  }

  @Test
  public void testActivityContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationActivityWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ContextActivityImpl());
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class, options);
    String result = workflow.workflow1("input1");
    // A String under default key "test" should be empty.
    // By specifying ActivityOptions#contextPropagators we turned off the default new TestContextPropagator() propagator
    // on activity stub side
    assertEquals("activity_null_testing1234", result);
  }

  @Test
  public void testLocalActivityContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationLocalActivityWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ContextActivityImpl());
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class, options);
    String result = workflow.workflow1("input1");
    // A String under default key "test" should be empty.
    // By specifying ActivityOptions#contextPropagators we turned off the default new TestContextPropagator() propagator
    // on activity stub side
    assertEquals("activity_null_testing1234", result);
  }
}
