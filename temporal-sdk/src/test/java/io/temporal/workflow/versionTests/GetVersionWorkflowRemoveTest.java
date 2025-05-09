package io.temporal.workflow.versionTests;

import static io.temporal.internal.history.VersionMarkerUtils.TEMPORAL_CHANGE_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionWorkflowRemoveTest extends BaseVersionTest {

  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              getDefaultWorkflowImplementationOptions(), TestGetVersionWorkflowRemove.class)
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
    assertEquals("bar10", workflowStub.execute(testWorkflowRule.getTaskQueue()));
    assertTrue(hasReplayed);
    List<String> versions =
        WorkflowStub.fromTyped(workflowStub)
            .describe()
            .getTypedSearchAttributes()
            .get(TEMPORAL_CHANGE_VERSION);
    if (upsertVersioningSA) {
      // Test that even though getVersion is removed, the versioning SA still contains the version.
      assertEquals(2, versions.size());
      assertEquals("changeFoo-1", versions.get(0));
      assertEquals("changeBar-2", versions.get(1));
    } else {
      assertEquals(null, versions);
    }
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
      activities.activity2("foo", 10);
      Workflow.sleep(1000); // forces new workflow task

      int changeBar = Workflow.getVersion("changeBar", Workflow.DEFAULT_VERSION, 2);
      if (changeBar != 2) {
        throw new IllegalStateException("Unexpected version: " + 1);
      }
      result = activities.activity2("bar", 10);
      Workflow.sleep(1000); // forces new workflow task
      return result;
    }
  }
}
