package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.testing.WorkflowReplayer;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Test;

/**
 * A local activity stub without timeouts must fail with a message that names the activity, as it
 * does for regular activities, for example when a history is replayed without the {@code
 * WorkflowImplementationOptions} that provided the defaults in production.
 */
public class LocalActivityMissingTimeoutsTest {

  @Test
  public void replayReportsLocalActivityWithoutTimeouts() {
    try {
      WorkflowReplayer.replayWorkflowExecutionFromResource(
          "laSuccessfulCompletion_1_17.json", WorkflowWithoutLocalActivityOptions.class);
      fail("Replay was expected to fail");
    } catch (Exception e) {
      String expected =
          "Both StartToCloseTimeout and ScheduleToCloseTimeout aren't specified for Activity2 local"
              + " activity. Please set at least one of the above through the LocalActivityOptions"
              + " of the stub or WorkflowImplementationOptions.";
      assertTrue(
          "Expected the failure to contain: " + expected + "\nActual: " + e.getMessage(),
          e.getMessage().contains(expected));
    }
  }

  public static class WorkflowWithoutLocalActivityOptions
      implements TestWorkflows.TestWorkflowReturnString {
    @Override
    public String execute() {
      TestActivities.VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(TestActivities.VariousTestActivities.class);
      return localActivities.activity2("input", 1);
    }
  }
}
