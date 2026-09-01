package io.temporal.internal.worker;

import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.payload.storage.TestStorageDriver;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowWorkerExternalStorageFailureTest {

  private static final TestStorageDriver driver = TestStorageDriver.named("wf-flaky");

  private static final ExternalStorage storage =
      ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0).build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(EchoWorkflowImpl.class)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder().setExternalStorage(storage).build())
          .build();

  @Before
  public void resetDriver() {
    driver.reset();
  }

  @Test
  public void aFailedOutboundStoreFailsTheWorkflowTaskInsteadOfTimingOut() throws Exception {
    String workflowId = "extstore-wft-" + UUID.randomUUID();
    String input = "wft-store-" + UUID.randomUUID();
    driver.failStoresContaining("echo: " + input, 1);

    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestWorkflow1.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowId(workflowId)
                    .build());
    WorkflowClient.start(workflow::execute, input);

    awaitEvent(workflowId, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    Assert.assertEquals(
        "expected exactly one injected store failure", 1, driver.injectedFailures.get());
    testWorkflowRule.assertHistoryEvent(workflowId, EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
    String reported =
        testWorkflowRule
            .getHistoryEvent(workflowId, EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED)
            .getWorkflowTaskFailedEventAttributes()
            .getFailure()
            .getMessage();
    Assert.assertTrue(
        "the reported failure must say what went wrong, got: " + reported,
        reported.contains("storage unavailable"));
    Assert.assertTrue(
        "a reported failure must not leave a workflow task timeout in history",
        testWorkflowRule
            .getHistoryEvents(workflowId, EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT)
            .isEmpty());
  }

  @Test
  public void aStorageFailureIsReportedOnEveryAttemptNotJustTheFirst() throws Exception {
    String workflowId = "extstore-wft-retry-" + UUID.randomUUID();
    String input = "wft-retry-" + UUID.randomUUID();
    driver.failStoresContaining("echo: " + input, 2);

    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestWorkflow1.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowId(workflowId)
                    .build());
    WorkflowClient.start(workflow::execute, input);

    awaitEvent(workflowId, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    Assert.assertEquals("expected two injected store failures", 2, driver.injectedFailures.get());
    Assert.assertTrue(
        "a reported failure must not leave a workflow task timeout in history",
        testWorkflowRule
            .getHistoryEvents(workflowId, EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT)
            .isEmpty());
  }

  private void awaitEvent(String workflowId, EventType eventType) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    while (System.nanoTime() < deadline) {
      if (!testWorkflowRule.getHistoryEvents(workflowId, eventType).isEmpty()) {
        return;
      }
      Thread.sleep(100);
    }
    Assert.fail("timed out waiting for " + eventType + " on " + workflowId);
  }

  public static class EchoWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      return "echo: " + input;
    }
  }
}
