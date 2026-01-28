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
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionMultipleCallsDefaultTest extends BaseVersionTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              getDefaultWorkflowImplementationOptions(), TestGetVersionWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  public GetVersionMultipleCallsDefaultTest(boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Test
  public void testGetVersionMultipleCallsDefault() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertEquals("activity1", result);
  }

  @Test
  public void testGetVersionMultipleCallsReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testGetVersionMultipleCallsHistoryDefault.json",
        GetVersionMultipleCallsDefaultTest.TestGetVersionWorkflowImpl.class);
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
      System.out.println("Calling getVersion for the fist time");
      if (WorkflowUnsafe.isReplaying()) {
        int version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
        assertEquals(Workflow.DEFAULT_VERSION, version);

        // Try again in the same WFT
        version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
        assertEquals(Workflow.DEFAULT_VERSION, version);
      }

      // Create a new WFT by sleeping
      Workflow.sleep(1000);
      int version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
      assertEquals(Workflow.DEFAULT_VERSION, version);

      String result = "activity" + testActivities.activity1(1);

      version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
      assertEquals(Workflow.DEFAULT_VERSION, version);
      return result;
    }
  }
}
