package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetVersionAddNewBeforeTest extends BaseVersionTest {

  private static final Logger log = LoggerFactory.getLogger(GetVersionAddNewBeforeTest.class);
  private static int versionFoo;
  private static int versionBar;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              getDefaultWorkflowImplementationOptions(), TestGetVersionWorkflowAddNewBefore.class)
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionAddNewBefore() {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);
    NoArgsWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    workflowStub.execute();
    assertEquals(1, versionFoo);
    assertEquals(Workflow.DEFAULT_VERSION, versionBar);
  }

  public static class TestGetVersionWorkflowAddNewBefore implements NoArgsWorkflow {

    @Override
    public void execute() {
      log.info("TestGetVersionWorkflow3Impl this=" + this.hashCode());
      // Test adding a version check in replay code.
      if (!WorkflowUnsafe.isReplaying()) {
        // The first version of the code
        assertEquals(1, Workflow.getVersion("changeFoo", Workflow.DEFAULT_VERSION, 1));
      } else {
        // The updated code
        versionFoo = Workflow.getVersion("changeFoo", Workflow.DEFAULT_VERSION, 1);
        versionBar = Workflow.getVersion("changeBar", Workflow.DEFAULT_VERSION, 1);
      }
      Workflow.sleep(1000); // forces new workflow task
    }
  }
}
