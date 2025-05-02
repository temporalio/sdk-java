package io.temporal.workflow.activityTests;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LongLocalActivityWorkflowTaskHeartbeatTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  /**
   * Emulates a workflow that triggers a long local activity, that sleeps longer than Workflow Task
   * Timeout.
   *
   * <p>This test makes sure than such a workflow and workflow task is not getting timed out by
   * performing heartbeats even while it takes longer than Workflow Task Timeout.
   *
   * @see LocalActivitiesWorkflowTaskHeartbeatTest
   */
  @Test
  public void testLongLocalActivityWorkflowTaskHeartbeat() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflow1 workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("sleepActivity123", result);
    Assert.assertEquals(activitiesImpl.toString(), 1, activitiesImpl.invocations.size());
  }

  public static class TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl
      implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newLocalActivityOptions20sScheduleToClose());
      return localActivities.sleepActivity(5000, 123);
    }
  }
}
