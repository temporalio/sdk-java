package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;

import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionMultipleCallsTest extends BaseVersionTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionMultipleCalls() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertEquals("activity1", result);
  }

  @Test
  public void testGetVersionMultipleCallsReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testGetVersionMultipleCallsHistory.json",
        GetVersionMultipleCallsTest.TestGetVersionWorkflowImpl.class);
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
      int version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
      assertEquals(version, 1);

      // Try again in the same WFT
      version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
      assertEquals(version, 1);

      // Create a new WFT by sleeping
      Workflow.sleep(1000);
      version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
      assertEquals(version, 1);
      String result = "activity" + testActivities.activity1(1);

      version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
      assertEquals(version, 1);

      return result;
    }
  }
}
