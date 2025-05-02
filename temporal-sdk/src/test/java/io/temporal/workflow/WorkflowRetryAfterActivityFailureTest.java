package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TerminatedFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivity1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowRetryAfterActivityFailureTest {

  private static AtomicInteger failureCounter = new AtomicInteger(1);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowImpl.class)
          .setActivityImplementations(new FailingActivityImpl())
          .build();

  @Test
  public void testWorkflowRetryAfterActivityFailure() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow1 workflow =
        client.newWorkflowStub(
            TestWorkflow1.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                // Retry the workflow though
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                .validateBuildWithDefaults());

    // Retry once
    assertEquals("bar", workflow.execute("input"));
    assertEquals(-1, failureCounter.get());

    // Non retryable failure
    try {
      failureCounter = new AtomicInteger(1);
      workflow.execute("terminated");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof TerminatedFailure);
      assertEquals(0, failureCounter.get());
    }
  }

  public static class WorkflowImpl implements TestWorkflow1 {
    private final TestActivity1 activity =
        Workflow.newActivityStub(
            TestActivity1.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(1))
                // Don't retry the activity
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .validateAndBuildWithDefaults());

    @Override
    public String execute(String input) {
      if (input.equals("terminated") && failureCounter.getAndDecrement() > 0) {
        throw new TerminatedFailure(input, null);
      }
      return activity.execute(input);
    }
  }

  public static class FailingActivityImpl implements TestActivity1 {
    @Override
    public String execute(String input) {
      if (failureCounter.getAndDecrement() > 0) {
        if (input.equals("terminated")) {
          throw new TerminatedFailure(input, null);
        } else {
          throw ApplicationFailure.newFailure("fail", "fail");
        }
      } else {
        return "bar";
      }
    }
  }
}
