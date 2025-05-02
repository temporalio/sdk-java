package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

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

public class GetVersionWorkflowRemoveTest extends BaseVersionTest {

  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionWorkflowRemove.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionWorkflowRemove() {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    assertEquals("foo10", workflowStub.execute(testWorkflowRule.getTaskQueue()));
    assertTrue(hasReplayed);
  }

  public static class TestGetVersionWorkflowRemove implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities activities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
      String result;
      // Test adding a version check in replay code.
      if (!WorkflowUnsafe.isReplaying()) {
        // The first version of the code
        int changeFoo = Workflow.getVersion("changeFoo", Workflow.DEFAULT_VERSION, 1);
        if (changeFoo != 1) {
          throw new IllegalStateException("Unexpected version: " + 1);
        }
      } else {
        // No getVersionCall
        hasReplayed = true;
      }
      result = activities.activity2("foo", 10);
      Workflow.sleep(1000); // forces new workflow task
      return result;
    }
  }
}
