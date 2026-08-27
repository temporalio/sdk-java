package io.temporal.internal.worker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.SlotSupplier;
import org.junit.Test;

public class NexusWorkerTest {

  @Test
  public void interruptingShutdownCancelsInFlightStorage() throws Exception {
    NexusWorker worker = worker();

    worker.shutdown(new ShutdownManager(), true).get();

    assertTrue(worker.storageCancellation.token().isCancellationRequested());
  }

  @Test
  public void gracefulShutdownLeavesStorageRunning() throws Exception {
    NexusWorker worker = worker();

    worker.shutdown(new ShutdownManager(), false).get();

    assertFalse(worker.storageCancellation.token().isCancellationRequested());
  }

  @SuppressWarnings("unchecked")
  private static NexusWorker worker() {
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    when(service.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.getDefaultInstance());
    return new NexusWorker(
        service,
        "ns",
        "tq",
        SingleWorkerOptions.newBuilder().build(),
        mock(NexusTaskHandler.class),
        DefaultDataConverter.newDefaultInstance(),
        mock(SlotSupplier.class),
        mock(NamespaceCapabilities.class));
  }
}
