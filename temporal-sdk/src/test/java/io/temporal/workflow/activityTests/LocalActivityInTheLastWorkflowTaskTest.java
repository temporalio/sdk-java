package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestActivities;
import java.time.Duration;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class LocalActivityInTheLastWorkflowTaskTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  @Test
  @Parameters({"true, true", "false, true", "true, false", "false, false"})
  public void testLocalActivityInTheLastWorkflowTask(boolean blockOnLA, boolean continueAsNew) {
    TestWorkflow client = testWorkflowRule.newWorkflowStub(TestWorkflow.class);
    assertEquals("done", client.execute(blockOnLA, continueAsNew));
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String execute(boolean blockOnLA, boolean continueAsNew);
  }

  public static class TestWorkflowImpl implements TestWorkflow {

    private final TestActivities.VariousTestActivities activities =
        Workflow.newLocalActivityStub(
            TestActivities.VariousTestActivities.class,
            LocalActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(200))
                .build());

    @Override
    public String execute(boolean blockOnLA, boolean continueAsNew) {
      if (blockOnLA) {
        Promise promise = Async.procedure(activities::sleepActivity, (long) 100, 0);
        Async.procedure(activities::sleepActivity, (long) 1000, 0);
        promise.get();
      }
      Async.procedure(activities::sleepActivity, (long) 1000, 0);
      if (continueAsNew) {
        Workflow.continueAsNew(blockOnLA, false);
      }
      return "done";
    }
  }
}
