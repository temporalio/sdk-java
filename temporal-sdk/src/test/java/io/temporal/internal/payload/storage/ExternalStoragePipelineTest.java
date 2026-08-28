package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.payload.storage.ExternalStorageOptions;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;

/**
 * End-to-end round trip through the worker and client pipelines: with external storage configured
 * and a zero threshold (offload everything), a workflow that passes payloads through an activity
 * must still complete correctly, and the driver must have actually stored and restored payloads.
 */
public class ExternalStoragePipelineTest {

  private final InMemoryDriver driver = new InMemoryDriver("test");

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(EchoWorkflowImpl.class)
          .setActivityImplementations(new EchoActivityImpl())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setExternalStorage(
                      ExternalStorageOptions.newBuilder()
                          .setDriver(driver)
                          .setPayloadSizeThreshold(0)
                          .build())
                  .build())
          .build();

  @Test
  public void payloadsRoundTripThroughStorage() {
    EchoWorkflow workflow = testWorkflowRule.newWorkflowStub(EchoWorkflow.class);

    String result = workflow.run("hello");

    assertEquals("echo: hello", result);
    assertEquals("echo: hello", workflow.lastResult());
    assertTrue("expected the driver to have stored payloads", driver.stores.get() > 0);
    assertTrue("expected the driver to have restored payloads", driver.retrieves.get() > 0);
    assertTrue(
        "expected an activity storage target",
        driver.targets.stream().anyMatch(StorageDriverActivityInfo.class::isInstance));
    assertTrue(
        "workflow run IDs must not be used as activity run IDs",
        driver.targets.stream()
            .filter(StorageDriverActivityInfo.class::isInstance)
            .map(StorageDriverActivityInfo.class::cast)
            .allMatch(target -> target.getRunId() == null));
  }

  @Test
  public void clientWithoutExternalStorageReadingAReferenceFails() {
    String workflowId = "extstore-not-configured-" + testWorkflowRule.getTaskQueue();
    EchoWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                EchoWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowId(workflowId)
                    .build());
    workflow.run("hello");

    WorkflowClient withoutStorage =
        WorkflowClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            WorkflowClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                .build());
    WorkflowStub stub = withoutStorage.newUntypedWorkflowStub(workflowId);

    Exception e = assertThrows(Exception.class, () -> stub.getResult(String.class));
    assertTrue(e.toString(), chainContains(e, "[TMPRL1105]"));
  }

  @Test
  public void offlineReplayerResolvesReferences() throws Exception {
    String workflowId = "extstore-replay-" + testWorkflowRule.getTaskQueue();
    EchoWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                EchoWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowId(workflowId)
                    .build());
    workflow.run("hello");

    WorkflowClient withoutStorage =
        WorkflowClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            WorkflowClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                .build());
    WorkflowExecutionHistory rawHistory = withoutStorage.fetchHistory(workflowId);

    TestWorkflowEnvironment replayEnv =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder()
                        .setExternalStorage(
                            ExternalStorageOptions.newBuilder()
                                .setDriver(driver)
                                .setPayloadSizeThreshold(0)
                                .build())
                        .build())
                .build());
    try {
      WorkflowReplayer.replayWorkflowExecution(rawHistory, replayEnv, EchoWorkflowImpl.class);
    } finally {
      replayEnv.close();
    }
  }

  private static boolean chainContains(Throwable t, String needle) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c.getMessage() != null && c.getMessage().contains(needle)) {
        return true;
      }
    }
    return false;
  }

  @WorkflowInterface
  public interface EchoWorkflow {
    @WorkflowMethod
    String run(String input);

    @QueryMethod
    String lastResult();
  }

  @ActivityInterface
  public interface EchoActivity {
    @ActivityMethod
    String echo(String input);
  }

  public static class EchoWorkflowImpl implements EchoWorkflow {
    private final EchoActivity activity =
        Workflow.newActivityStub(
            EchoActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());
    private String lastResult = "";

    @Override
    public String run(String input) {
      lastResult = activity.echo(input);
      return lastResult;
    }

    @Override
    public String lastResult() {
      return lastResult;
    }
  }

  public static class EchoActivityImpl implements EchoActivity {
    @Override
    public String echo(String input) {
      Activity.getExecutionContext().heartbeat("heartbeat: " + input);
      return "echo: " + input;
    }
  }

  private static final class InMemoryDriver implements StorageDriver {
    private final String name;
    private final Map<String, Payload> objects = new ConcurrentHashMap<>();
    private final List<StorageDriverTargetInfo> targets =
        Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger stores = new AtomicInteger();
    final AtomicInteger retrieves = new AtomicInteger();
    private final AtomicInteger counter = new AtomicInteger();

    InMemoryDriver(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getType() {
      return "test.inmemory";
    }

    @Override
    public CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      targets.add(context.getTarget());
      List<StorageDriverClaim> claims = new ArrayList<>(payloads.size());
      for (Payload payload : payloads) {
        stores.incrementAndGet();
        String key = name + "-" + counter.incrementAndGet();
        objects.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      List<Payload> payloads = new ArrayList<>(claims.size());
      for (StorageDriverClaim claim : claims) {
        retrieves.incrementAndGet();
        payloads.add(objects.get(claim.getClaimData().get("key")));
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }
}
