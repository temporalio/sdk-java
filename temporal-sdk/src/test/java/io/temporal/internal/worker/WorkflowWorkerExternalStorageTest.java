package io.temporal.internal.worker;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.payload.storage.TestStorageDriver;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** e2e tests */
public class WorkflowWorkerExternalStorageTest {

  private static final int THRESHOLD = 4096;
  private static final String CONTINUED_MARKER = "continued:";

  private static final TestStorageDriver driver = TestStorageDriver.named("wf-happy");

  private static final ExternalStorage storage =
      ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(THRESHOLD).build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ContinueAsNewWorkflowImpl.class, EchoWorkflowImpl.class)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder().setExternalStorage(storage).build())
          .build();

  @Before
  public void resetDriver() {
    driver.reset();
  }

  @Test
  public void aLargeContinueAsNewInputIsStoredThenRestoredOnTheContinuedRun() {
    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestWorkflow1.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowId("extstore-can-" + UUID.randomUUID())
                    .build());

    // Blocking call follows the continue-as-new and returns the continued run's result.
    String result = workflow.execute("start");

    int expectedInputLength = CONTINUED_MARKER.length() + THRESHOLD * 2;
    Assert.assertEquals(
        "the continued run must observe the full restored input, not a storage reference",
        "len:" + expectedInputLength,
        result);
    Assert.assertTrue("the worker must have offloaded a payload", driver.stores.get() > 0);
    Assert.assertTrue("the continued run must have retrieved it", driver.retrieves.get() > 0);
    Assert.assertTrue(
        "the large continue-as-new input must be what was stored", driver.stored(CONTINUED_MARKER));
  }

  @Test
  public void aResultUnderTheThresholdIsLeftInlineAndReadableByTheClient() {
    EchoWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                EchoWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowId("extstore-inline-" + UUID.randomUUID())
                    .build());

    String result = workflow.echo("small");

    Assert.assertEquals("echo: small", result);
    Assert.assertEquals("nothing under the threshold should be offloaded", 0, driver.stores.get());
  }

  public static class ContinueAsNewWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      if (input.startsWith(CONTINUED_MARKER)) {
        return "len:" + input.length();
      }
      StringBuilder large = new StringBuilder(CONTINUED_MARKER);
      for (int i = 0; i < THRESHOLD * 2; i++) {
        large.append('x');
      }
      Workflow.newContinueAsNewStub(TestWorkflows.TestWorkflow1.class).execute(large.toString());
      throw new IllegalStateException("unreachable: continue-as-new ends the run");
    }
  }

  @WorkflowInterface
  public interface EchoWorkflow {
    @WorkflowMethod
    String echo(String input);
  }

  public static class EchoWorkflowImpl implements EchoWorkflow {
    @Override
    public String echo(String input) {
      return "echo: " + input;
    }
  }
}
