package io.temporal.internal.worker;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class AsyncWorkflowPollerBalancingTest {
  @Test(timeout = 10000)
  public void onlyStickyResponsesUpdateBacklogIncludingEmptyResponses() throws Exception {
    WorkflowServiceStubs service = mockService();
    when(service.futureStub().pollWorkflowTaskQueue(any()))
        .thenReturn(
            Futures.immediateFuture(
                PollWorkflowTaskQueueResponse.newBuilder().setBacklogCountHint(10).build()),
            Futures.immediateFuture(
                PollWorkflowTaskQueueResponse.newBuilder().setBacklogCountHint(10).build()),
            Futures.immediateFuture(PollWorkflowTaskQueueResponse.getDefaultInstance()));
    TrackingSlotSupplier<WorkflowSlotInfo> slots =
        new TrackingSlotSupplier<>(new FixedSizeSlotSupplier<>(10), new NoopScope());
    WorkflowPollerBalancer balancer = spy(new WorkflowPollerBalancer(Optional.of(10), 3));
    AsyncWorkflowPollTask sticky = pollTask(service, slots, true, balancer);
    AsyncWorkflowPollTask normal = pollTask(service, slots, false, balancer);

    normal.onPollerTargetChanged(1);
    verify(balancer, never()).setStickyTarget(anyInt());
    sticky.onPollerTargetChanged(2);
    verify(balancer).setStickyTarget(2);

    assertNull(normal.poll(new SlotPermit()).get(5, TimeUnit.SECONDS));
    verify(balancer, never()).recordStickyBacklog(anyLong());
    assertNull(sticky.poll(new SlotPermit()).get(5, TimeUnit.SECONDS));
    verify(balancer).recordStickyBacklog(10);
    assertNull(sticky.poll(new SlotPermit()).get(5, TimeUnit.SECONDS));
    verify(balancer).recordStickyBacklog(0);
  }

  @Test(timeout = 10000)
  public void stickyPoolDoesNotReserveAnExtraSlotAtItsTarget() throws Exception {
    WorkflowServiceStubs service = mockService();
    SettableFuture<PollWorkflowTaskQueueResponse> response = SettableFuture.create();
    when(service.futureStub().pollWorkflowTaskQueue(any())).thenReturn(response);
    TrackingSlotSupplier<WorkflowSlotInfo> slots =
        new TrackingSlotSupplier<>(new FixedSizeSlotSupplier<>(10), new NoopScope());
    WorkflowPollerBalancer balancer = new WorkflowPollerBalancer(Optional.of(10), 1);
    AtomicReference<Thread> stickyThread = new AtomicReference<>();
    AtomicReference<Thread> normalThread = new AtomicReference<>();
    AsyncWorkflowPollTask sticky = spy(pollTask(service, slots, true, balancer));
    AsyncWorkflowPollTask normal = spy(pollTask(service, slots, false, balancer));
    capturePollThread(sticky, stickyThread);
    capturePollThread(normal, normalThread);
    ShutdownableTaskExecutor<WorkflowTask> executor = mock(ShutdownableTaskExecutor.class);
    AsyncPoller<WorkflowTask> poller =
        new AsyncPoller<>(
            slots,
            new SlotReservationData("queue", "identity", ""),
            Arrays.asList(sticky, normal),
            executor,
            PollerOptions.newBuilder()
                .setPollThreadNamePrefix("balancing-test")
                .setPollerBehavior(new PollerBehaviorAutoscaling(1, 3, 1))
                .build(),
            new NamespaceCapabilities(),
            new NoopScope());
    poller.start();
    try {
      assertEventually(
          Duration.ofSeconds(5),
          () -> {
            assertNotNull(stickyThread.get());
            assertNotNull(normalThread.get());
            assertEquals(Thread.State.WAITING, stickyThread.get().getState());
            assertEquals(Thread.State.WAITING, normalThread.get().getState());
            // One slot per outstanding poll, plus a normal slot waiting on poller concurrency.
            // Sticky must wait for admission BEFORE reserving a second slot.
            assertEquals(3, slots.getIssuedSlots());
          });
      verify(sticky, times(1)).onPollStarted();
      verify(normal, times(1)).onPollStarted();
      verify(service.futureStub(), times(2)).pollWorkflowTaskQueue(any());
    } finally {
      poller.shutdown(new ShutdownManager(), true).get(5, TimeUnit.SECONDS);
      response.setException(Status.CANCELLED.asRuntimeException());
      poller.awaitTermination(5, TimeUnit.SECONDS);
    }
    assertEventually(Duration.ofSeconds(5), () -> assertEquals(0, slots.getIssuedSlots()));
  }

  private static void capturePollThread(AsyncWorkflowPollTask task, AtomicReference<Thread> thread)
      throws Exception {
    doAnswer(
            invocation -> {
              thread.set(Thread.currentThread());
              return invocation.callRealMethod();
            })
        .when(task)
        .awaitPollAdmission();
  }

  private static WorkflowServiceStubs mockService() {
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    WorkflowServiceGrpc.WorkflowServiceFutureStub stub =
        mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
    when(service.futureStub()).thenReturn(stub);
    when(stub.withOption(any(), any())).thenReturn(stub);
    return service;
  }

  private static AsyncWorkflowPollTask pollTask(
      WorkflowServiceStubs service,
      TrackingSlotSupplier<WorkflowSlotInfo> slots,
      boolean sticky,
      WorkflowPollerBalancer balancer) {
    AsyncWorkflowPollTask task =
        new AsyncWorkflowPollTask(
            service,
            "default",
            "queue",
            sticky ? "sticky-queue" : null,
            "identity",
            "instance-key",
            new WorkerVersioningOptions("", false, null),
            slots,
            new NoopScope(),
            GetSystemInfoResponse.Capabilities::getDefaultInstance,
            new PollerTracker(),
            null);
    task.setPollerBalancer(balancer);
    return task;
  }
}
