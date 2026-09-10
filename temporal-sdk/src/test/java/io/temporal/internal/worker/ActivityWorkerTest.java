package io.temporal.internal.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.SlotSupplier;
import org.junit.Test;

public class ActivityWorkerTest {

  @Test
  public void standaloneActivityTargetsTheActivity() {
    PollActivityTaskQueueResponse response =
        PollActivityTaskQueueResponse.newBuilder()
            .setActivityId("act-1")
            .setActivityRunId("run-1")
            .setActivityType(ActivityType.newBuilder().setName("MyActivity"))
            .build();

    StorageDriverTargetInfo target = ActivityWorker.storageTargetForActivityTask("ns", response);

    assertEquals(new StorageDriverActivityInfo("ns", "act-1", "run-1", "MyActivity"), target);
  }

  @Test
  public void workflowActivityTargetsTheWorkflow() {
    PollActivityTaskQueueResponse response =
        PollActivityTaskQueueResponse.newBuilder()
            .setActivityId("act-1")
            .setActivityType(ActivityType.newBuilder().setName("MyActivity"))
            .setWorkflowType(WorkflowType.newBuilder().setName("MyWorkflow"))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId("wf-1").setRunId("wf-run-1"))
            .build();

    StorageDriverTargetInfo target = ActivityWorker.storageTargetForActivityTask("ns", response);

    assertEquals(new StorageDriverWorkflowInfo("ns", "wf-1", "wf-run-1", "MyWorkflow"), target);
  }

  @Test
  public void interruptingShutdownCancelsInFlightStorage() throws Exception {
    ActivityWorker worker = worker();

    worker.shutdown(new ShutdownManager(), true).get();

    assertTrue(worker.storageCancellation.token().isCancellationRequested());
  }

  @Test
  public void gracefulShutdownLeavesStorageRunning() throws Exception {
    ActivityWorker worker = worker();

    worker.shutdown(new ShutdownManager(), false).get();

    assertFalse(worker.storageCancellation.token().isCancellationRequested());
  }

  @SuppressWarnings("unchecked")
  private static ActivityWorker worker() {
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    when(service.getServerCapabilities())
        .thenReturn(
            () ->
                io.temporal.api.workflowservice.v1.GetSystemInfoResponse.Capabilities
                    .getDefaultInstance());
    return new ActivityWorker(
        service,
        "ns",
        "tq",
        1.0,
        SingleWorkerOptions.newBuilder().build(),
        mock(ActivityTaskHandler.class),
        mock(SlotSupplier.class),
        mock(NamespaceCapabilities.class));
  }
}
