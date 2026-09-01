package io.temporal.internal.worker;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowWorkerExternalStorageFailureTest {

  private static final FlakyDriver driver = new FlakyDriver("wf-flaky");

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
    driver.failStoresContaining.set("echo: " + input);

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
        "expected exactly one injected store failure", 1, driver.injectedStoreFailures.get());
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
    driver.failStoresContaining.set("echo: " + input);
    driver.failuresToInject.set(2);

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
        "expected two injected store failures", 2, driver.injectedStoreFailures.get());
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

  private static final class FlakyDriver implements StorageDriver {
    private final String name;
    private final Map<String, Payload> objects = new HashMap<>();
    final AtomicReference<String> failStoresContaining = new AtomicReference<>();
    final AtomicInteger failuresToInject = new AtomicInteger(1);
    final AtomicInteger injectedStoreFailures = new AtomicInteger();
    private int counter = 0;

    FlakyDriver(String name) {
      this.name = name;
    }

    synchronized void reset() {
      objects.clear();
      failStoresContaining.set(null);
      failuresToInject.set(1);
      injectedStoreFailures.set(0);
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getType() {
      return "test.wf.flaky";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      String marker = failStoresContaining.get();
      if (marker != null) {
        for (Payload payload : payloads) {
          if (payload.getData().toStringUtf8().contains(marker)) {
            if (failuresToInject.decrementAndGet() <= 0) {
              failStoresContaining.set(null);
            }
            injectedStoreFailures.incrementAndGet();
            CompletableFuture<List<StorageDriverClaim>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("storage unavailable"));
            return failed;
          }
        }
      }
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = name + "-" + (counter++);
        objects.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public synchronized CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      List<Payload> payloads = new ArrayList<>();
      for (StorageDriverClaim claim : claims) {
        payloads.add(objects.get(claim.getClaimData().get("key")));
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }
}
