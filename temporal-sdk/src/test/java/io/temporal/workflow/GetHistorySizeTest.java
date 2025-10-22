package io.temporal.workflow;

import static org.junit.Assert.*;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class GetHistorySizeTest {
  private static final TestActivities.VariousTestActivities activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void replay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testGetHistorySize.json", TestWorkflowImpl.class);
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

      assertEquals(408, Workflow.getInfo().getHistorySize());
      assertFalse(Workflow.getInfo().isContinueAsNewSuggested());

      // Force WFT heartbeat
      activities.sleepActivity(TimeUnit.SECONDS.toMillis(10), 1);

      assertEquals(897, Workflow.getInfo().getHistorySize());
      assertTrue(Workflow.getInfo().isContinueAsNewSuggested());

      return "done";
    }
  }
}
