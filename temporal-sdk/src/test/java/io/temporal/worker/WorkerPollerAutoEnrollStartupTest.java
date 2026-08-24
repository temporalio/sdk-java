package io.temporal.worker;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.namespace.v1.NamespaceInfo.Capabilities;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.ShutdownWorkerRequest;
import io.temporal.api.workflowservice.v1.ShutdownWorkerResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.internal.sync.WorkflowThreadExecutor;
import io.temporal.internal.worker.NamespaceCapabilities;
import io.temporal.internal.worker.ShutdownManager;
import io.temporal.internal.worker.WorkflowExecutorCache;
import io.temporal.internal.worker.WorkflowRunLockManager;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestNexusServices;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Full start-path wiring test for poller-autoscaling auto-enrollment: when the namespace advertises
 * the capability, starting a worker with default options switches the workflow, activity, and nexus
 * pollers' effective behavior to {@link PollerBehaviorAutoscaling}.
 */
public class WorkerPollerAutoEnrollStartupTest {

  @WorkflowInterface
  public interface DemoWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class DemoWorkflowImpl implements DemoWorkflow {
    @Override
    public void run() {}
  }

  @ActivityInterface
  public interface DemoActivity {
    @ActivityMethod
    void doThing();
  }

  public static class DemoActivityImpl implements DemoActivity {
    @Override
    public void doThing() {}
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class DemoNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync((ctx, details, name) -> "Hello " + name);
    }
  }

  @Test
  public void autoEnrollAtStartupSwitchesPollersToAutoscaling() throws Exception {
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    when(service.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());

    // Async pollers poll via futureStub().withOption(...).pollXxxTaskQueue(...). Return futures
    // that
    // never complete so the started poller threads park harmlessly until shutdown cancels them.
    WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
        mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
    when(service.futureStub()).thenReturn(futureStub);
    when(futureStub.withOption(any(), any())).thenReturn(futureStub);
    when(futureStub.pollWorkflowTaskQueue(any())).thenReturn(SettableFuture.create());
    when(futureStub.pollActivityTaskQueue(any())).thenReturn(SettableFuture.create());
    when(futureStub.pollNexusTaskQueue(any())).thenReturn(SettableFuture.create());
    when(futureStub.shutdownWorker(any(ShutdownWorkerRequest.class)))
        .thenReturn(Futures.immediateFuture(ShutdownWorkerResponse.newBuilder().build()));

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

    // Namespace advertises the auto-enroll capability.
    NamespaceCapabilities capabilities = new NamespaceCapabilities();
    capabilities.setFromCapabilities(
        Capabilities.newBuilder().setPollerAutoscalingAutoEnroll(true).build());

    Scope metricsScope = new NoopScope();
    WorkflowRunLockManager runLocks = new WorkflowRunLockManager();
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, runLocks, metricsScope);
    WorkflowThreadExecutor wfThreadExecutor = mock(WorkflowThreadExecutor.class);

    Worker worker =
        new Worker(
            client,
            "test-task-queue",
            WorkerFactoryOptions.newBuilder().build(),
            WorkerOptions.newBuilder().build(),
            metricsScope,
            runLocks,
            cache,
            true,
            wfThreadExecutor,
            Collections.emptyList(),
            Collections.emptyList(),
            "test-worker-group",
            capabilities);

    // Register all three task types so each worker starts its poller.
    worker.registerWorkflowImplementationTypes(DemoWorkflowImpl.class);
    worker.registerActivitiesImplementations(new DemoActivityImpl());
    worker.registerNexusServiceImplementation(new DemoNexusServiceImpl());

    worker.start();
    try {
      assertTrue(
          "workflow pollers should be autoscaling",
          worker.workflowWorker.getWorkflowPollerOptions().getPollerBehavior()
              instanceof PollerBehaviorAutoscaling);
      assertTrue(
          "activity pollers should be autoscaling",
          worker.activityWorker.getPollerOptions().getPollerBehavior()
              instanceof PollerBehaviorAutoscaling);
      assertTrue(
          "nexus pollers should be autoscaling",
          worker.nexusWorker.getPollerOptions().getPollerBehavior()
              instanceof PollerBehaviorAutoscaling);
    } finally {
      worker.shutdown(new ShutdownManager(), true).get(5, TimeUnit.SECONDS);
    }
  }
}
