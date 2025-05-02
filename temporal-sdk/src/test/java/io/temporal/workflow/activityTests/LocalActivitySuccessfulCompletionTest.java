package io.temporal.workflow.activityTests;

import io.temporal.client.WorkflowStub;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivitySuccessfulCompletionTest {
  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testSuccessfulCompletion() {
    TestWorkflows.TestWorkflowReturnString workflowStub =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class);
    String result = workflowStub.execute();
    Assert.assertEquals("input1", result);
    Assert.assertEquals(activitiesImpl.toString(), 1, activitiesImpl.invocations.size());
    testWorkflowRule.regenerateHistoryForReplay(
        WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId(),
        "laSuccessfulCompletion_1_xx");
  }

  /** History from 1.17 before we changed LA marker structure in 1.18 */
  @Test
  public void testSuccessfulCompletion_replay117() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "laSuccessfulCompletion_1_17.json",
        TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl.class);
  }

  public static class TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl
      implements TestWorkflows.TestWorkflowReturnString {
    @Override
    public String execute() {
      TestActivities.VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.VariousTestActivities.class,
              SDKTestOptions.newLocalActivityOptions20sScheduleToClose());
      return localActivities.activity2("input", 1);
    }
  }
}
