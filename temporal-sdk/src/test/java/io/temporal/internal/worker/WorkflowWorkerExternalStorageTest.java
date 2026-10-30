package io.temporal.internal.worker;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.payload.storage.TestStorageDriver;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.testing.CloudTestExclusion.NeedsCloudAdaptation;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** e2e tests */
@CloudTestExclusionNote(
    "Cloud envconfig replaces the custom external storage option used by this test.")
@Category(NeedsCloudAdaptation.class)
public class WorkflowWorkerExternalStorageTest {

  private static final int THRESHOLD = 4096;
  private static final String CONTINUED_MARKER = "continued:";
  private static final String ACTIVITY_ARG_MARKER = "activity-arg:";
  private static final String ACTIVITY_RESULT_MARKER = "activity-result:";

  private static final TestStorageDriver driver = TestStorageDriver.named("wf-happy");

  private static final ExternalStorage storage =
      ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(THRESHOLD).build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              ContinueAsNewWorkflowImpl.class,
              EchoWorkflowImpl.class,
              LargeActivityArgWorkflowImpl.class)
          .setActivityImplementations(new TransformActivityImpl())
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

  @Test
  public void aLargeActivityArgumentIsDownloadedOnlyByTheActivityWorker() {
    LargeActivityArgWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                LargeActivityArgWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowId("extstore-activity-arg-" + UUID.randomUUID())
                    .build());

    // The workflow returns the length of the large activity result, which it must download.
    int expectedLength = ACTIVITY_RESULT_MARKER.length() + THRESHOLD * 2;
    Assert.assertEquals("len:" + expectedLength, workflow.execute());
    // A query on the closed workflow replays its full history.
    Assert.assertEquals("len:" + expectedLength, workflow.lastResult());

    Assert.assertEquals(
        "only the activity worker should download the activity argument",
        1,
        downloads(ACTIVITY_ARG_MARKER));
    Assert.assertTrue(
        "the workflow worker must still download the activity result",
        downloads(ACTIVITY_RESULT_MARKER) > 0);
  }

  /** How many times the stored payload containing {@code marker} was downloaded. */
  private static int downloads(String marker) {
    for (int i = 0; i < driver.storedData.size(); i++) {
      if (driver.storedData.get(i).contains(marker)) {
        return Collections.frequency(driver.retrievedKeys, "wf-happy-" + i);
      }
    }
    throw new AssertionError("no payload containing " + marker + " was offloaded");
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

  @WorkflowInterface
  public interface LargeActivityArgWorkflow {
    @WorkflowMethod
    String execute();

    @QueryMethod
    String lastResult();
  }

  public static class LargeActivityArgWorkflowImpl implements LargeActivityArgWorkflow {
    private String lastResult;

    @Override
    public String execute() {
      StringBuilder large = new StringBuilder(ACTIVITY_ARG_MARKER);
      for (int i = 0; i < THRESHOLD * 2; i++) {
        large.append('x');
      }
      TransformActivity activity =
          Workflow.newActivityStub(
              TransformActivity.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());
      String result = activity.transform(large.toString());
      lastResult =
          result.startsWith(ACTIVITY_RESULT_MARKER) ? "len:" + result.length() : "wrong result";
      return lastResult;
    }

    @Override
    public String lastResult() {
      return lastResult;
    }
  }

  @ActivityInterface
  public interface TransformActivity {
    String transform(String input);
  }

  public static class TransformActivityImpl implements TransformActivity {
    @Override
    public String transform(String input) {
      return input.replace(ACTIVITY_ARG_MARKER, ACTIVITY_RESULT_MARKER);
    }
  }
}
