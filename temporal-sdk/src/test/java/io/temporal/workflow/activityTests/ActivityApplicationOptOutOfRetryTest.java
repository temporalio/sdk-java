package io.temporal.workflow.activityTests;

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityApplicationOptOutOfRetryTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestActivityApplicationOptOutOfRetry.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testActivityApplicationOptOutOfRetry() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    try {
      workflowStub.execute(testWorkflowRule.getTaskQueue());
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          "simulatedType", ((ApplicationFailure) e.getCause().getCause()).getType());
    }

    // Since maximum attempts is set to 1, there should be no retries at all
    Assert.assertEquals(1, activitiesImpl.applicationFailureCounter.get());
  }

  public static class TestActivityApplicationOptOutOfRetry implements TestWorkflow1 {

    private VariousTestActivities activities;

    @Override
    public String execute(String taskQueue) {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setTaskQueue(taskQueue)
              .setScheduleToCloseTimeout(Duration.ofSeconds(200))
              .setStartToCloseTimeout(Duration.ofSeconds(1))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build();
      activities = Workflow.newActivityStub(VariousTestActivities.class, options);
      activities.throwApplicationFailureThreeTimes();
      return "ignored";
    }
  }
}
