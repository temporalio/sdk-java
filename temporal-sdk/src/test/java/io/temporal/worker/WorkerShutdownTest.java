package io.temporal.worker;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.common.util.concurrent.Futures;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.enums.v1.WorkerStatus;
import io.temporal.api.worker.v1.WorkerHeartbeat;
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
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestNexusServices;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class WorkerShutdownTest {

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    @Override
    public void run() {}
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    void doThing();
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public void doThing() {}
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync((ctx, details, now) -> "Hello " + now);
    }
  }

  /**
   * Verifies that the active task queue types in the ShutdownWorkerRequest are evaluated at
   * shutdown time, not at Worker construction time. Types registered after construction must be
   * reflected in the request.
   */
  @Test
  public void activeTaskQueueTypesEvaluatedAtShutdownTime() throws Exception {
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    when(service.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());

    WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
        mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
    when(service.futureStub()).thenReturn(futureStub);
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
            new NamespaceCapabilities());

    // Register types AFTER worker construction. The request built by shutdown should reflect
    // these registrations, proving that getActiveTaskQueueTypes() is evaluated lazily.
    worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TestActivityImpl());
    worker.registerNexusServiceImplementation(new TestNexusServiceImpl());
    Supplier<WorkerHeartbeat> heartbeatSupplier =
        () -> WorkerHeartbeat.newBuilder().setStatus(WorkerStatus.WORKER_STATUS_RUNNING).build();
    worker.setHeartbeatSupplier(heartbeatSupplier);

    worker.shutdown(new ShutdownManager(), true).get(5, TimeUnit.SECONDS);

    ArgumentCaptor<ShutdownWorkerRequest> captor =
        ArgumentCaptor.forClass(ShutdownWorkerRequest.class);
    verify(futureStub).shutdownWorker(captor.capture());
    List<TaskQueueType> shutdownTypes = captor.getValue().getTaskQueueTypesList();
    assertTrue(
        "ShutdownWorkerRequest should include WORKFLOW type registered after construction",
        shutdownTypes.contains(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW));
    assertTrue(
        "ShutdownWorkerRequest should include ACTIVITY type registered after construction",
        shutdownTypes.contains(TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY));
    assertTrue(
        "ShutdownWorkerRequest should include NEXUS type registered after construction",
        shutdownTypes.contains(TaskQueueType.TASK_QUEUE_TYPE_NEXUS));
    assertEquals(
        "ShutdownWorkerRequest heartbeat should report SHUTTING_DOWN",
        WorkerStatus.WORKER_STATUS_SHUTTING_DOWN,
        captor.getValue().getWorkerHeartbeat().getStatus());
    assertTrue(
        "ShutdownWorkerRequest sticky task queue should be derived from worker identity",
        captor.getValue().getStickyTaskQueue().startsWith("test-worker:"));
  }
}
