package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.CloudTestExclusion.RequiresLocalServer;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

public class GetVersionSameIdOnReplayTest extends BaseVersionTest {

  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              getDefaultWorkflowImplementationOptions(), TestGetVersionSameIdOnReplay.class)
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  public GetVersionSameIdOnReplayTest(boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @CloudTestExclusionNote("This replay test depends on local test-server execution behavior.")
  @Category(RequiresLocalServer.class)
  @Test
  public void testGetVersionSameIdOnReplay() {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);
    NoArgsWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    workflowStub.execute();
    assertTrue(hasReplayed);
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    testWorkflowRule.assertNoHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_MARKER_RECORDED);
  }

  public static class TestGetVersionSameIdOnReplay implements NoArgsWorkflow {

    @Override
    public void execute() {
      // Test adding a version check in replay code.
      if (!WorkflowUnsafe.isReplaying()) {
        Workflow.sleep(Duration.ofMinutes(1));
      } else {
        hasReplayed = true;
        int version2 = Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 11);
        Workflow.sleep(Duration.ofMinutes(1));
        int version3 = Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 11);

        assertEquals(Workflow.DEFAULT_VERSION, version3);
        assertEquals(version2, version3);
      }
    }
  }
}
