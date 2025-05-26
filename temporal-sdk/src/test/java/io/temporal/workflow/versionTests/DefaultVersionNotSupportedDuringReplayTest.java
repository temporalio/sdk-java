package io.temporal.workflow.versionTests;

import io.temporal.client.WorkflowClient;
import io.temporal.internal.Signal;
import io.temporal.internal.statemachines.UnsupportedVersion;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies a situation with a workflow is executed without versioning and after that is getting
 * replayed on a code version that doesn't support the {@link
 * io.temporal.workflow.Workflow#DEFAULT_VERSION} anymore
 */
public class DefaultVersionNotSupportedDuringReplayTest extends BaseVersionTest {

  private static final Signal unsupportedVersionExceptionThrown = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              getDefaultWorkflowImplementationOptions(), TestVersionNotSupportedWorkflowImpl.class)
          .build();

  public DefaultVersionNotSupportedDuringReplayTest(
      boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Test
  public void testVersionNotSupported() throws InterruptedException {
    TestWorkflowReturnString workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflowReturnString.class);

    WorkflowClient.start(workflowStub::execute);

    unsupportedVersionExceptionThrown.waitForSignal();
  }

  public static class TestVersionNotSupportedWorkflowImpl implements TestWorkflowReturnString {

    @Override
    public String execute() {
      if (WorkflowUnsafe.isReplaying()) {
        try {
          Workflow.getVersion("test_change", 2, 3);
        } catch (UnsupportedVersion e) {
          Assert.assertEquals(
              "Version -1 of changeId test_change is not supported. Supported v is between 2 and 3.",
              e.getMessage());
          unsupportedVersionExceptionThrown.signal();
          throw e;
        }
      }

      Workflow.sleep(Duration.ofMillis(500));
      throw new RuntimeException(); // force replay by failing WFT
    }
  }
}
