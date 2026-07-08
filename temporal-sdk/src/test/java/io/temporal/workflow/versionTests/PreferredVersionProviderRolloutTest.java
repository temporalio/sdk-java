package io.temporal.workflow.versionTests;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.PreferredVersionProvider;
import io.temporal.worker.VersionPreference;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

public class PreferredVersionProviderRolloutTest {
  private static final String CHANGE_ID = "preferred-change";
  private static final AtomicInteger unactivatedProviderCalls = new AtomicInteger();
  private static final AtomicInteger activatedProviderCalls = new AtomicInteger();
  private static final AtomicInteger oldWorkerSignals = new AtomicInteger();
  private static final AtomicInteger unactivatedNewWorkerSignals = new AtomicInteger();

  @Before
  public void setUp() {
    unactivatedProviderCalls.set(0);
    activatedProviderCalls.set(0);
    oldWorkerSignals.set(0);
    unactivatedNewWorkerSignals.set(0);
  }

  @Test
  public void unactivatedNewGetVersionCallReplaysOnOldWorkerWithoutTheCall() throws Exception {
    String taskQueue = "preferred-version-provider-rollout-" + UUID.randomUUID();
    try (TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance()) {
      WorkerFactory newWorkerFactory =
          WorkerFactory.newInstance(newClient(testEnvironment, "new-rollout-worker"));
      Worker newWorker =
          newWorkerFactory.newWorker(
              taskQueue,
              workerOptions(
                  (input) -> {
                    unactivatedProviderCalls.incrementAndGet();
                    return Optional.of(VersionPreference.of(Workflow.DEFAULT_VERSION));
                  }));
      newWorker.registerWorkflowImplementationTypes(NewRolloutWorkflowImpl.class);

      testEnvironment.start();
      newWorkerFactory.start();

      RolloutWorkflow workflow = newWorkflowStub(testEnvironment, taskQueue);
      WorkflowClient.start(workflow::run);

      assertEventually(
          Duration.ofSeconds(5), () -> assertEquals(1, unactivatedProviderCalls.get()));
      assertEquals("new-0", workflow.state());

      shutdown(newWorkerFactory);

      WorkerFactory oldWorkerFactory =
          WorkerFactory.newInstance(newClient(testEnvironment, "old-rollout-worker"));
      Worker oldWorker = oldWorkerFactory.newWorker(taskQueue, workerOptions(null));
      oldWorker.registerWorkflowImplementationTypes(OldRolloutWorkflowImpl.class);
      oldWorkerFactory.start();

      workflow.release();
      testEnvironment.sleep(Duration.ofSeconds(1));

      assertEventually(Duration.ofSeconds(5), () -> assertEquals(1, oldWorkerSignals.get()));

      workflow.release();
      assertEquals("old", WorkflowStub.fromTyped(workflow).getResult(String.class));
      shutdown(oldWorkerFactory);
    }
  }

  @Test
  public void activatedGetVersionCallReplaysOnUnactivatedNewWorker() throws Exception {
    String taskQueue = "preferred-version-provider-rollout-" + UUID.randomUUID();
    try (TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance()) {
      WorkerFactory activatedWorkerFactory =
          WorkerFactory.newInstance(newClient(testEnvironment, "activated-rollout-worker"));
      Worker activatedWorker =
          activatedWorkerFactory.newWorker(
              taskQueue,
              workerOptions(
                  (input) -> {
                    activatedProviderCalls.incrementAndGet();
                    return Optional.of(VersionPreference.of(1));
                  }));
      activatedWorker.registerWorkflowImplementationTypes(NewRolloutWorkflowImpl.class);

      testEnvironment.start();
      activatedWorkerFactory.start();

      RolloutWorkflow workflow = newWorkflowStub(testEnvironment, taskQueue);
      WorkflowClient.start(workflow::run);

      assertEventually(Duration.ofSeconds(5), () -> assertEquals(1, activatedProviderCalls.get()));
      assertEquals("new-0", workflow.state());

      shutdown(activatedWorkerFactory);

      WorkerFactory unactivatedWorkerFactory =
          WorkerFactory.newInstance(newClient(testEnvironment, "unactivated-rollout-worker"));
      Worker unactivatedWorker =
          unactivatedWorkerFactory.newWorker(
              taskQueue,
              workerOptions(
                  (input) -> {
                    unactivatedProviderCalls.incrementAndGet();
                    return Optional.of(VersionPreference.of(Workflow.DEFAULT_VERSION));
                  }));
      unactivatedWorker.registerWorkflowImplementationTypes(
          UnactivatedNewRolloutWorkflowImpl.class);
      unactivatedWorkerFactory.start();

      workflow.release();
      testEnvironment.sleep(Duration.ofSeconds(1));

      assertEventually(
          Duration.ofSeconds(5), () -> assertEquals(1, unactivatedNewWorkerSignals.get()));

      workflow.release();
      assertEquals("new", WorkflowStub.fromTyped(workflow).getResult(String.class));
      assertEquals(0, unactivatedProviderCalls.get());
      shutdown(unactivatedWorkerFactory);
    }
  }

  private static RolloutWorkflow newWorkflowStub(
      TestWorkflowEnvironment testEnvironment, String taskQueue) {
    return testEnvironment
        .getWorkflowClient()
        .newWorkflowStub(
            RolloutWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(taskQueue)
                .setWorkflowId(UUID.randomUUID().toString())
                .build());
  }

  private static WorkerOptions workerOptions(PreferredVersionProvider preferredVersionProvider) {
    return WorkerOptions.newBuilder()
        .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
        .setPreferredVersionProvider(preferredVersionProvider)
        .build();
  }

  private static WorkflowClient newClient(
      TestWorkflowEnvironment testEnvironment, String identity) {
    WorkflowClientOptions clientOptions =
        testEnvironment.getWorkflowClient().getOptions().toBuilder()
            .setIdentity(identity + "-" + UUID.randomUUID())
            .build();
    return WorkflowClient.newInstance(testEnvironment.getWorkflowServiceStubs(), clientOptions);
  }

  private static void shutdown(WorkerFactory workerFactory) throws InterruptedException {
    workerFactory.shutdownNow();
    workerFactory.awaitTermination(10, TimeUnit.SECONDS);
  }

  @WorkflowInterface
  public interface RolloutWorkflow {
    @WorkflowMethod
    String run();

    @SignalMethod
    void release();

    @QueryMethod
    String state();
  }

  public static class OldRolloutWorkflowImpl implements RolloutWorkflow {
    private int releases;

    @Override
    public String run() {
      Workflow.await(() -> releases >= 1);
      Workflow.await(() -> releases >= 2);
      return "old";
    }

    @Override
    public void release() {
      oldWorkerSignals.incrementAndGet();
      releases++;
    }

    @Override
    public String state() {
      return "old-" + releases;
    }
  }

  public static class NewRolloutWorkflowImpl implements RolloutWorkflow {
    private int releases;

    @Override
    public String run() {
      int version = Workflow.getVersion(CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
      Workflow.await(() -> releases >= 1);
      assertEquals(version, Workflow.getVersion(CHANGE_ID, Workflow.DEFAULT_VERSION, 1));
      Workflow.await(() -> releases >= 2);
      return version == Workflow.DEFAULT_VERSION ? "old" : "new";
    }

    @Override
    public void release() {
      releases++;
    }

    @Override
    public String state() {
      return "new-" + releases;
    }
  }

  public static class UnactivatedNewRolloutWorkflowImpl extends NewRolloutWorkflowImpl {
    @Override
    public void release() {
      unactivatedNewWorkerSignals.incrementAndGet();
      super.release();
    }
  }
}
