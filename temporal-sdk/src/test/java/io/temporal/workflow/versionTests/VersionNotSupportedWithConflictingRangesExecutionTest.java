package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowException;
import io.temporal.failure.ApplicationFailure;
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

/**
 * Verifies a situation with a workflow having two getVersion calls.These calls have version ranges
 * that are incompatible with each other.
 */
public class VersionNotSupportedWithConflictingRangesExecutionTest extends BaseVersionTest {

  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              getDefaultWorkflowImplementationOptions(),
              WorkflowWithIncompatibleRangesForTheSameChangeId.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  public VersionNotSupportedWithConflictingRangesExecutionTest(
      boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Test
  public void testVersionNotSupported() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    try {
      workflowStub.execute(testWorkflowRule.getTaskQueue());
      fail("unreachable");
    } catch (WorkflowException e) {
      assertEquals(
          "message='unsupported change version', type='test', nonRetryable=false",
          e.getCause().getMessage());
    }
    assertTrue(hasReplayed);
  }

  public static class WorkflowWithIncompatibleRangesForTheSameChangeId implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      hasReplayed = WorkflowUnsafe.isReplaying();
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      // Test adding a version check in non-replay code.
      int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
      String result = "";
      if (version == Workflow.DEFAULT_VERSION) {
        result += "activity" + testActivities.activity1(1);
      } else {
        result += testActivities.activity2("activity2", 2); // This is executed.
      }

      try {
        Workflow.getVersion("test_change", 2, 3);
      } catch (Error e) {
        throw Workflow.wrap(ApplicationFailure.newFailure("unsupported change version", "test"));
      }
      return result;
    }
  }
}
