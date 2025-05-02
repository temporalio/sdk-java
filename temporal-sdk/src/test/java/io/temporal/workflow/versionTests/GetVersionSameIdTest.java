package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionSameIdTest extends BaseVersionTest {

  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionSameId.class)
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionSameId() {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    NoArgsWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    workflowStub.execute();
    assertTrue(hasReplayed);
  }

  public static class TestGetVersionSameId implements NoArgsWorkflow {

    @Override
    public void execute() {
      System.out.println("REPLAYING: " + WorkflowUnsafe.isReplaying());
      // Test adding a version check in replay code.
      if (!WorkflowUnsafe.isReplaying()) {
        Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 11);
        Workflow.sleep(Duration.ofMinutes(1));
      } else {
        hasReplayed = true;
        int version1 = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 11);
        Workflow.sleep(Duration.ofMinutes(1));
        int version2 = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 11);

        assertEquals(11, version2);
        assertEquals(version1, version2);
      }
    }
  }
}
