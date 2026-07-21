package io.temporal.worker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.internal.sync.WorkflowThreadExecutor;
import io.temporal.internal.worker.NamespaceCapabilities;
import io.temporal.internal.worker.WorkflowExecutorCache;
import io.temporal.internal.worker.WorkflowRunLockManager;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import java.util.Collections;
import org.junit.Test;

/**
 * Verifies that {@link Worker} derives per-poller-type auto-enroll eligibility from whether the
 * user called a poller setter (tracked on {@link WorkerOptions.Builder}) and threads it into each
 * internal worker's poller options.
 *
 * <p>This guards the subtlest part of poller-autoscaling auto-enrollment: eligibility must reflect
 * the user's intent, not the resolved value. Options from {@code getDefaultInstance()} or {@code
 * validateAndBuildWithDefaults()} carry the numeric default poller count (5) yet must stay
 * eligible, while an explicit count of 5 must not. Value-based inference cannot tell these apart.
 */
public class WorkerPollerAutoEnrollEligibilityTest {

  private Worker buildWorker(WorkerOptions options) {
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    when(service.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(service.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    WorkflowClient client = mock(WorkflowClient.class);
    when(client.getWorkflowServiceStubs()).thenReturn(service);
    when(client.getOptions())
        .thenReturn(
            WorkflowClientOptions.newBuilder()
                .setNamespace("test-ns")
                .setIdentity("test-worker")
                .validateAndBuildWithDefaults());

    Scope metricsScope = new NoopScope();
    WorkflowRunLockManager runLocks = new WorkflowRunLockManager();
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, runLocks, metricsScope);
    WorkflowThreadExecutor wfThreadExecutor = mock(WorkflowThreadExecutor.class);

    return new Worker(
        client,
        "test-task-queue",
        WorkerFactoryOptions.newBuilder().build(),
        options,
        metricsScope,
        runLocks,
        cache,
        true,
        wfThreadExecutor,
        Collections.emptyList(),
        Collections.emptyList(),
        "test-worker-group",
        new NamespaceCapabilities());
  }

  private boolean workflowEligible(Worker worker) {
    return worker.workflowWorker.getWorkflowPollerOptions().isAutoscalingAutoEnrollEligible();
  }

  private boolean activityEligible(Worker worker) {
    return worker.activityWorker.getPollerOptions().isAutoscalingAutoEnrollEligible();
  }

  private boolean nexusEligible(Worker worker) {
    return worker.nexusWorker.getPollerOptions().isAutoscalingAutoEnrollEligible();
  }

  @Test
  public void defaultOptionsMakeEveryPollerTypeEligible() {
    Worker worker = buildWorker(WorkerOptions.newBuilder().build());
    assertTrue(workflowEligible(worker));
    assertTrue(activityEligible(worker));
    assertTrue(nexusEligible(worker));
  }

  @Test
  public void nullOptionsMakeEveryPollerTypeEligible() {
    // WorkerFactory.newWorker(taskQueue) passes null options through to the Worker constructor.
    Worker worker = buildWorker(null);
    assertTrue(workflowEligible(worker));
    assertTrue(activityEligible(worker));
    assertTrue(nexusEligible(worker));
  }

  @Test
  public void explicitMaxConcurrentPollersMakeAllTypesIneligible() {
    Worker worker =
        buildWorker(
            WorkerOptions.newBuilder()
                .setMaxConcurrentWorkflowTaskPollers(4)
                .setMaxConcurrentActivityTaskPollers(3)
                .setMaxConcurrentNexusTaskPollers(2)
                .build());
    assertFalse(workflowEligible(worker));
    assertFalse(activityEligible(worker));
    assertFalse(nexusEligible(worker));
  }

  @Test
  public void explicitMaxConcurrentPollersOnOneTypeLeavesOthersEligible() {
    Worker worker =
        buildWorker(WorkerOptions.newBuilder().setMaxConcurrentWorkflowTaskPollers(4).build());
    assertFalse(workflowEligible(worker));
    assertTrue(activityEligible(worker));
    assertTrue(nexusEligible(worker));
  }

  @Test
  public void defaultInstanceMakesEveryPollerTypeEligible() {
    // getDefaultInstance() carries the numeric default poller count (5), but the user never called
    // a
    // setter, so all types remain eligible.
    Worker worker = buildWorker(WorkerOptions.getDefaultInstance());
    assertTrue(workflowEligible(worker));
    assertTrue(activityEligible(worker));
    assertTrue(nexusEligible(worker));
  }

  @Test
  public void validateAndBuildWithDefaultsMakesEveryPollerTypeEligible() {
    Worker worker = buildWorker(WorkerOptions.newBuilder().validateAndBuildWithDefaults());
    assertTrue(workflowEligible(worker));
    assertTrue(activityEligible(worker));
    assertTrue(nexusEligible(worker));
  }

  @Test
  public void copyOfDefaultInstanceIsEligible() {
    // Provenance must survive newBuilder(options), for both build paths.
    Worker fromBuild =
        buildWorker(WorkerOptions.newBuilder(WorkerOptions.getDefaultInstance()).build());
    assertTrue(workflowEligible(fromBuild));
    assertTrue(activityEligible(fromBuild));
    assertTrue(nexusEligible(fromBuild));

    Worker fromValidate =
        buildWorker(
            WorkerOptions.newBuilder(WorkerOptions.getDefaultInstance())
                .validateAndBuildWithDefaults());
    assertTrue(workflowEligible(fromValidate));
    assertTrue(activityEligible(fromValidate));
    assertTrue(nexusEligible(fromValidate));
  }

  @Test
  public void explicitCountEqualToNumericDefaultIsIneligible() {
    // Explicitly setting the count to its numeric default (5) still counts as "configured".
    Worker worker =
        buildWorker(WorkerOptions.newBuilder().setMaxConcurrentWorkflowTaskPollers(5).build());
    assertFalse(workflowEligible(worker));
    assertTrue(activityEligible(worker));
    assertTrue(nexusEligible(worker));
  }

  @Test
  public void explicitPollerBehaviorMakesOnlyThatTypeIneligible() {
    Worker worker =
        buildWorker(
            WorkerOptions.newBuilder()
                .setActivityTaskPollersBehavior(new PollerBehaviorSimpleMaximum(3))
                .build());
    // Only the activity poller was configured explicitly; workflow and nexus stay eligible.
    assertFalse(activityEligible(worker));
    assertTrue(workflowEligible(worker));
    assertTrue(nexusEligible(worker));
  }
}
