package io.temporal.activity;

import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityHeartbeatSentOnFailureTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new HeartBeatingActivityImpl())
          .build();

  /** Tests that the last Activity#heartbeat value is sent if the activity fails. */
  @Test
  public void activityHeartbeatSentOnFailure() {
    TestWorkflows.NoArgsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
    workflow.execute();
  }

  public static class TestWorkflowImpl implements TestWorkflows.NoArgsWorkflow {

    private final TestActivities.NoArgsActivity activities =
        Workflow.newActivityStub(
            TestActivities.NoArgsActivity.class,
            SDKTestOptions.newActivityOptions20sScheduleToClose());

    @Override
    public void execute() {
      activities.execute();
    }
  }

  public static class HeartBeatingActivityImpl implements TestActivities.NoArgsActivity {
    @Override
    public void execute() {
      // If the heartbeat details are "3", then we know that the last heartbeat was sent.
      if (Activity.getExecutionContext().getHeartbeatDetails(String.class).orElse("").equals("3")) {
        Activity.getExecutionContext().heartbeat("1");
        // Verify that last heartbeat details don't change after a heartbeat
        Assert.assertEquals(
            "3", Activity.getExecutionContext().getLastHeartbeatDetails(String.class).orElse(""));
        return;
      }
      // Send 3 heartbeats and then fail, expecting the last heartbeat to be sent
      // even though the activity fails and the last two attempts would normally be throttled.
      Activity.getExecutionContext().heartbeat("1");
      Activity.getExecutionContext().heartbeat("2");
      Activity.getExecutionContext().heartbeat("3");
      throw new RuntimeException("simulated failure");
    }
  }
}
