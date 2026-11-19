package io.temporal.internal.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.payload.storage.TestStorageDriver;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.PayloadCache;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Test environments use a payload cache set in their client options. The replayer's own environment
 * has none.
 */
public class TestEnvironmentPayloadCacheTest {
  private static final String TASK_QUEUE = "payload-cache";

  public @Rule Timeout timeout = Timeout.seconds(30);

  private final TestStorageDriver driver = TestStorageDriver.named("env-cache");
  private final CountingCache cache = new CountingCache();

  @Test
  public void workflowEnvironmentClientsAndWorkersUseTheCache() {
    try (TestWorkflowEnvironment testEnvironment = newWorkflowEnvironment()) {
      runEchoWorkflow(testEnvironment, "wf-" + UUID.randomUUID());

      assertTrue("payloads should have been offloaded", driver.stores.get() > 0);
      assertTrue(cache.gets.get() > 0);
      assertTrue(cache.sets.get() > 0);
    }
  }

  @Test
  public void replayerUsesTheCacheOfThePassedEnvironment() throws Exception {
    WorkflowExecutionHistory history;
    try (TestWorkflowEnvironment testEnvironment = newWorkflowEnvironment()) {
      String workflowId = "wf-" + UUID.randomUUID();
      runEchoWorkflow(testEnvironment, workflowId);
      history = testEnvironment.getWorkflowClient().fetchHistory(workflowId);
    }
    int getsBeforeReplay = cache.gets.get();

    try (TestWorkflowEnvironment replayEnvironment = newUnstartedWorkflowEnvironment()) {
      WorkflowReplayer.replayWorkflowExecution(history, replayEnvironment, EchoWorkflowImpl.class);
    }

    assertTrue(cache.gets.get() > getsBeforeReplay);
  }

  /**
   * {@link WorkflowReplayer} methods that take no environment or Worker replay on {@link
   * TestWorkflowEnvironment#newInstance()}.
   */
  @Test
  public void theReplayersOwnEnvironmentHasNoCache() {
    try (TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance()) {
      assertSame(
          PayloadCache.disabled(),
          testEnvironment.getWorkflowClient().getOptions().getPayloadCache());
    }
  }

  @Test
  public void activityEnvironmentUsesTheCache() {
    TestActivityEnvironment testEnvironment =
        TestActivityEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(clientOptionsWithCache())
                .build());
    try {
      testEnvironment.registerActivitiesImplementations(new HeartbeatActivityImpl());

      assertEquals(
          "input",
          testEnvironment.newActivityStub(HeartbeatActivity.class).heartbeatAndEcho("input"));

      assertTrue("heartbeat details should have been offloaded", driver.stores.get() > 0);
      assertTrue(cache.sets.get() > 0);
    } finally {
      testEnvironment.close();
    }
  }

  private TestWorkflowEnvironment newWorkflowEnvironment() {
    TestWorkflowEnvironment testEnvironment = newUnstartedWorkflowEnvironment();
    testEnvironment
        .newWorker(TASK_QUEUE)
        .registerWorkflowImplementationTypes(EchoWorkflowImpl.class);
    testEnvironment.start();
    return testEnvironment;
  }

  private TestWorkflowEnvironment newUnstartedWorkflowEnvironment() {
    return TestWorkflowEnvironment.newInstance(
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(clientOptionsWithCache())
            .build());
  }

  private WorkflowClientOptions clientOptionsWithCache() {
    return WorkflowClientOptions.newBuilder()
        .setExternalStorage(
            ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0).build())
        .setPayloadCache(cache)
        .build();
  }

  private static void runEchoWorkflow(TestWorkflowEnvironment testEnvironment, String workflowId) {
    EchoWorkflow workflow =
        testEnvironment
            .getWorkflowClient()
            .newWorkflowStub(
                EchoWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(TASK_QUEUE)
                    .setWorkflowId(workflowId)
                    .build());
    assertEquals("echo: hello", workflow.echo("hello"));
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

  @ActivityInterface
  public interface HeartbeatActivity {
    String heartbeatAndEcho(String input);
  }

  public static class HeartbeatActivityImpl implements HeartbeatActivity {
    @Override
    public String heartbeatAndEcho(String input) {
      Activity.getExecutionContext().heartbeat("details");
      return input;
    }
  }

  /** Misses on every lookup, declines every write, and counts both. */
  private static final class CountingCache implements PayloadCache {
    final AtomicInteger gets = new AtomicInteger();
    final AtomicInteger sets = new AtomicInteger();

    @Nonnull
    @Override
    public CompletableFuture<Optional<Payload>> get(@Nonnull String key) {
      gets.incrementAndGet();
      return CompletableFuture.completedFuture(Optional.empty());
    }

    @Nonnull
    @Override
    public CompletableFuture<Boolean> set(@Nonnull String key, @Nonnull Payload payload) {
      sets.incrementAndGet();
      return CompletableFuture.completedFuture(false);
    }
  }
}
