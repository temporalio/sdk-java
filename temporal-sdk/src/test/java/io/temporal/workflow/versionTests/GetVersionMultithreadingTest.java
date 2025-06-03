package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionMultithreadingTest extends BaseVersionTest {

  private static boolean hasReplayed;

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

  public GetVersionMultithreadingTest(boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Test
  public void testGetVersionDefaultMultithreading() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertTrue(hasReplayed);
    assertEquals("activity1", result);
  }

  @Test
  public void testGetVersionDefaultMultithreadingReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testGetVersionDefaultMultithreadingHistory.json", TestGetVersionWorkflowImpl.class);
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      Async.procedure(
          () -> {
            Workflow.sleep(1000);
          });

      // Test adding a version check in replay code with an additional thread running.
      if (WorkflowUnsafe.isReplaying()) {
        hasReplayed = true;
        int version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
        assertEquals(version, Workflow.DEFAULT_VERSION);
      }
      String result =
          "activity" + testActivities.activity1(1); // This is executed in non-replay mode.
      return result;
    }
  }
}
