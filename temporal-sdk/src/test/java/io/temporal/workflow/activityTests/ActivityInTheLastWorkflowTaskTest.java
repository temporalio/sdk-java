package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.ActivityOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class ActivityInTheLastWorkflowTaskTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  @Test
  public void testActivityInTheLastWorkflowTask() {
    TestWorkflows.TestWorkflowReturnString client =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class);
    assertEquals("done", client.execute());
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflowReturnString {

    private final TestActivities.VariousTestActivities activities =
        Workflow.newActivityStub(
            TestActivities.VariousTestActivities.class,
            ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(200))
                .build());

    @Override
    public String execute() {
      Async.procedure(activities::sleepActivity, (long) 1000, 0);
      return "done";
    }
  }
}
