package io.temporal.testserver.functional.timeskipping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ActivityTimeoutTimeSkippingTest {
  @Parameterized.Parameters(name = "sleep={0}s")
  public static Object[][] sleepDurations() {
    return new Object[][] {{4}, {6}};
  }

  @Parameterized.Parameter public int sleepSeconds;

  private final AtomicBoolean waited = new AtomicBoolean();
  private final AtomicInteger attempts = new AtomicInteger();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setNamespace(SDKTestWorkflowRule.NAMESPACE)
                  .setIdentity("activity-timeout-repro")
                  .build())
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new SleepingActivityImpl())
          .build();

  @Test
  public void activityCompletesAfterTimeSkipping() throws TimeoutException {
    TestWorkflows.PrimitiveWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.PrimitiveWorkflow.class);
    WorkflowClient.start(workflow::execute);
    try {
      WorkflowStub.fromTyped(workflow).getResult(3, TimeUnit.SECONDS, Void.class);
    } catch (TimeoutException e) {
      System.err.println(testWorkflowRule.getTestEnvironment().getDiagnostics());
      throw e;
    }
    if (sleepSeconds < 5) {
      assertEquals(1, attempts.get());
    } else {
      assertTrue(attempts.get() >= 2);
    }
  }

  public class SleepingActivityImpl implements SleepingActivity {
    @Override
    public void sleep() {
      attempts.incrementAndGet();
      if (!waited.get()) {
        testWorkflowRule.getTestEnvironment().sleep(Duration.ofSeconds(sleepSeconds));
        waited.set(true);
      }
    }
  }

  public static class TestWorkflowImpl implements TestWorkflows.PrimitiveWorkflow {
    @Override
    public void execute() {
      Workflow.newActivityStub(
              SleepingActivity.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build())
          .sleep();
      Workflow.sleep(Duration.ofHours(1));
    }
  }
}
