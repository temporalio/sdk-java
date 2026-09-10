package io.temporal.internal.worker;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
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

public class ActivityWorkerExternalStorageFailureTest {

  private static final String LARGE_RESULT = String.join("", Collections.nCopies(60, "0123456789"));

  private static final FlakyDriver driver = new FlakyDriver("activity-flaky");

  private static final ExternalStorage storage =
      ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(100).build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(LargeResultWorkflowImpl.class)
          .setActivityImplementations(new LargeResultActivityImpl())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder().setExternalStorage(storage).build())
          .build();

  @Before
  public void resetState() {
    driver.reset();
    LargeResultActivityImpl.attempts.set(0);
  }

  @Test
  public void aFailedOutboundStoreRetriesWithoutWaitingForTheActivityTimeout() {
    String workflowId = "extstore-activity-" + UUID.randomUUID();
    driver.failStoresContaining.set(LARGE_RESULT);

    LargeResultWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                LargeResultWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowId(workflowId)
                    .build());

    Assert.assertEquals("ok", workflow.execute());
    Assert.assertEquals(
        "expected exactly one injected store failure", 1, driver.injectedStoreFailures.get());
    Assert.assertEquals(
        "expected the activity to run twice", 2, LargeResultActivityImpl.attempts.get());
    Assert.assertTrue(
        "a reported failure must not leave an activity timeout in history",
        testWorkflowRule
            .getHistoryEvents(workflowId, EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT)
            .isEmpty());
  }

  @WorkflowInterface
  public interface LargeResultWorkflow {
    @WorkflowMethod
    String execute();
  }

  @ActivityInterface
  public interface LargeResultActivity {
    @ActivityMethod
    String run();
  }

  public static class LargeResultWorkflowImpl implements LargeResultWorkflow {
    @Override
    public String execute() {
      LargeResultActivity activity =
          Workflow.newActivityStub(
              LargeResultActivity.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(60))
                  .setRetryOptions(
                      RetryOptions.newBuilder()
                          .setInitialInterval(Duration.ofMillis(100))
                          .setMaximumAttempts(3)
                          .build())
                  .build());
      return activity.run();
    }
  }

  public static class LargeResultActivityImpl implements LargeResultActivity {
    static final AtomicInteger attempts = new AtomicInteger();

    @Override
    public String run() {
      return attempts.incrementAndGet() == 1 ? LARGE_RESULT : "ok";
    }
  }

  private static final class FlakyDriver implements StorageDriver {
    private final String name;
    private final Map<String, Payload> objects = new HashMap<>();
    final AtomicReference<String> failStoresContaining = new AtomicReference<>();
    final AtomicInteger injectedStoreFailures = new AtomicInteger();
    private int counter = 0;

    FlakyDriver(String name) {
      this.name = name;
    }

    synchronized void reset() {
      objects.clear();
      failStoresContaining.set(null);
      injectedStoreFailures.set(0);
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getType() {
      return "test.activity.flaky";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      String marker = failStoresContaining.get();
      if (marker != null) {
        for (Payload payload : payloads) {
          if (payload.getData().toStringUtf8().contains(marker)) {
            failStoresContaining.set(null);
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
