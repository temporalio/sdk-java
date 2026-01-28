package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class GetHistoryLengthTest {
  private static final TestActivities.VariousTestActivities activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test(timeout = 20000)
  public void getHistoryLength() {
    TestWorkflows.TestWorkflowReturnString workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.TestWorkflowReturnString.class);
    assertEquals("done", workflowStub.execute());

    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    testWorkflowRule.regenerateHistoryForReplay(execution.getWorkflowId(), "testGetHistoryLength");
  }

  @Test
  public void replay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testGetHistoryLength.json", TestWorkflowImpl.class);
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflowReturnString {

    @Override
    public String execute() {
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(30))
              .build();

      TestActivities.VariousTestActivities activities =
          Workflow.newLocalActivityStub(TestActivities.VariousTestActivities.class, options);

      assertEquals(3, Workflow.getInfo().getHistoryLength());

      // Force WFT heartbeat
      activities.sleepActivity(TimeUnit.SECONDS.toMillis(10), 1);

      assertEquals(9, Workflow.getInfo().getHistoryLength());

      return "done";
    }
  }
}
