package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionRemovedInReplayTest extends BaseVersionTest {

  public static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              getDefaultWorkflowImplementationOptions(), TestGetVersionRemovedInReplay.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  public GetVersionRemovedInReplayTest(boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Test
  public void testGetVersionRemovedInReplay() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertTrue(hasReplayed);
    assertEquals("activity22activity", result);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "getVersion",
            "executeActivity Activity2",
            "activity Activity2",
            "executeActivity Activity",
            "activity Activity");
  }

  /**
   * The following test covers the scenario where getVersion call is removed before a
   * non-version-marker command.
   */
  public static class TestGetVersionRemovedInReplay implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      hasReplayed = WorkflowUnsafe.isReplaying();
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
      String result;
      // Test removing a version check in replay code.
      if (!WorkflowUnsafe.isReplaying()) {
        int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 13);
        assertEquals(13, version);
      }
      result = testActivities.activity2("activity2", 2);
      result += testActivities.activity();
      return result;
    }
  }
}
