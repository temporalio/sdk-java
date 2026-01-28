package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.*;

import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.AngryChildActivityImpl;
import io.temporal.workflow.shared.TestWorkflows.AngryChild;
import io.temporal.workflow.shared.TestWorkflows.ITestChild;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowAsyncRetryTest {

  private final AngryChildActivityImpl angryChildActivity = new AngryChildActivityImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestChildWorkflowAsyncRetryWorkflow.class, AngryChild.class)
          .setActivityImplementations(angryChildActivity)
          .build();

  @Test
  public void testChildWorkflowAsyncRetry() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflow1 client =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    try {
      client.execute(testWorkflowRule.getTaskQueue());
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof ChildWorkflowFailure);
      assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      assertEquals("test", ((ApplicationFailure) e.getCause().getCause()).getType());
      assertEquals(
          "message='simulated failure', type='test', nonRetryable=false",
          e.getCause().getCause().getMessage());
    }
    assertEquals(3, angryChildActivity.getInvocationCount());
  }

  /** Tests that WorkflowReplayer fails if replay does not match workflow run. */
  @Test(expected = RuntimeException.class)
  public void testAlteredWorkflowReplayFailure() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testChildWorkflowRetryHistory.json", AlteredTestChildWorkflowRetryWorkflow.class);
  }

  public static class TestChildWorkflowAsyncRetryWorkflow implements TestWorkflow1 {

    private ITestChild child;

    public TestChildWorkflowAsyncRetryWorkflow() {}

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder()
              .setWorkflowRunTimeout(Duration.ofSeconds(5))
              .setWorkflowTaskTimeout(Duration.ofSeconds(2))
              .setTaskQueue(taskQueue)
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumAttempts(3)
                      .build())
              .build();
      child = Workflow.newChildWorkflowStub(ITestChild.class, options);
      return Async.function(child::execute, taskQueue, 0).get();
    }
  }

  public static class AlteredTestChildWorkflowRetryWorkflow
      extends TestChildWorkflowAsyncRetryWorkflow {

    public AlteredTestChildWorkflowRetryWorkflow() {}

    @Override
    public String execute(String taskQueue) {
      Workflow.sleep(Duration.ofMinutes(1));
      return super.execute(taskQueue);
    }
  }
}
