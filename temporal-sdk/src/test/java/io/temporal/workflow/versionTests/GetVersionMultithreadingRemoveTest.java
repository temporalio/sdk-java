package io.temporal.workflow.versionTests;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import io.temporal.internal.Issue;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

@Issue("https://github.com/temporalio/sdk-java/issues/2307")
public class GetVersionMultithreadingRemoveTest extends BaseVersionTest {

  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              getDefaultWorkflowImplementationOptions(), TestGetVersionWorkflowImpl.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  public GetVersionMultithreadingRemoveTest(boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Test
  public void testGetVersionMultithreadingRemoval() {
    assumeTrue("This test only passes if SKIP_YIELD_ON_VERSION is enabled", setVersioningFlag);
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertTrue(hasReplayed);
    assertEquals("activity1", result);
  }

  @Test
  public void testGetVersionMultithreadingRemovalReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testGetVersionMultithreadingRemoveHistory.json", TestGetVersionWorkflowImpl.class);
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities.VariousTestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      Async.procedure(
          () -> {
            Workflow.sleep(1000);
          });

      // Test removing a version check in replaying code with an additional thread running.
      if (!WorkflowUnsafe.isReplaying()) {
        int version = Workflow.getVersion("changeId", 1, 2);
        assertEquals(version, 2);
      } else {
        hasReplayed = true;
      }
      String result =
          "activity" + testActivities.activity1(1); // This is executed in non-replay mode.
      return result;
    }
  }
}
