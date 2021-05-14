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
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class WorkflowContextPropagationTest {
  private static final Logger log = LoggerFactory.getLogger(WorkflowContextPropagationTest.class);

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
                    .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
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

  @WorkflowInterface
  public interface ParentWorkflow {
    @WorkflowMethod
    String workflow(String input);

    @SignalMethod
    void signal(String value);
  }

  @WorkflowInterface
  public interface ChildWorkflow {
    @WorkflowMethod
    String workflow(String input, String parentId);
  }

  public static class ContextPropagationWorkflowImpl implements TestWorkflow {

    @Override
    public String workflow1(String input) {
      // The test value should be in the MDC
      return MDC.get("test");
    }
  }

  @Test
  public void testWorkflowContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationWorkflowImpl.class);
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
    assertEquals("testing123", result);
  }

  public static class ContextPropagationParentWorkflowImpl implements ParentWorkflow {

    @Override
    public String workflow(String input) {
      // Get the MDC value
      String mdcValue = MDC.get("test");

      // Fire up a child workflow
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder()
              .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
              .build();
      ChildWorkflow child = Workflow.newChildWorkflowStub(ChildWorkflow.class, options);

      String result = child.workflow(mdcValue, Workflow.getInfo().getWorkflowId());
      return result;
    }

    @Override
    public void signal(String value) {}
  }

  public static class ContextPropagationChildWorkflowImpl implements ChildWorkflow {

    @Override
    public String workflow(String input, String parentId) {
      String mdcValue = MDC.get("test");
      return input + mdcValue;
    }
  }

  @Test
  public void testChildWorkflowContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        ContextPropagationParentWorkflowImpl.class, ContextPropagationChildWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    ParentWorkflow workflow = client.newWorkflowStub(ParentWorkflow.class, options);
    String result = workflow.workflow("input1");
    assertEquals("testing123testing123", result);
  }

  public static class ContextPropagationThreadWorkflowImpl implements TestWorkflow {

    @Override
    public String workflow1(String input) {
      Promise<String> asyncPromise = Async.function(this::async);
      return asyncPromise.get();
    }

    private String async() {
      return "async" + MDC.get("test");
    }
  }

  @Test
  public void testThreadContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationThreadWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .setTaskQueue(TASK_QUEUE)
            .build();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class, options);
    String result = workflow.workflow1("input1");
    assertEquals("asynctesting123", result);
  }

  public static class ContextActivityImpl implements TestActivity {
    @Override
    public String activity(String input) {
      return "activity" + MDC.get("test");
    }
  }

  public static class DefaultContextPropagationActivityWorkflowImpl implements TestWorkflow {
    @Override
    public String workflow1(String input) {
      ActivityOptions options =
          ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(5)).build();
      TestActivity activity = Workflow.newActivityStub(TestActivity.class, options);
      return activity.activity("foo");
    }
  }

  public static class DefaultContextPropagationLocalActivityWorkflowImpl implements TestWorkflow {
    @Override
    public String workflow1(String input) {
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();
      TestActivity activity = Workflow.newLocalActivityStub(TestActivity.class, options);
      return activity.activity("foo");
    }
  }

  @Test
  public void testDefaultActivityContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(DefaultContextPropagationActivityWorkflowImpl.class);
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
    assertEquals("activitytesting123", result);
  }

  @Test
  public void testDefaultLocalActivityContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        DefaultContextPropagationLocalActivityWorkflowImpl.class);
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
    assertEquals("activitytesting123", result);
  }

  public static class DefaultContextPropagationParentWorkflowImpl implements ParentWorkflow {

    @Override
    public String workflow(String input) {
      // Get the MDC value
      String mdcValue = MDC.get("test");

      // Fire up a child workflow
      ChildWorkflowOptions options = ChildWorkflowOptions.newBuilder().build();
      ChildWorkflow child = Workflow.newChildWorkflowStub(ChildWorkflow.class, options);

      String result = child.workflow(mdcValue, Workflow.getInfo().getWorkflowId());
      return result;
    }

    @Override
    public void signal(String value) {}
  }

  @Test
  public void testDefaultChildWorkflowContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        DefaultContextPropagationParentWorkflowImpl.class,
        ContextPropagationChildWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    ParentWorkflow workflow = client.newWorkflowStub(ParentWorkflow.class, options);
    String result = workflow.workflow("input1");
    assertEquals("testing123testing123", result);
  }
}
