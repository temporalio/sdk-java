package io.temporal.internal.worker;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
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

  @Test(timeout = 30000)
  public void stickyBacklogGrowsIntoSlotsReleasedByNormalPolls() throws Exception {
    WorkflowServiceStubs service = mockService();
    BlockingQueue<SettableFuture<PollWorkflowTaskQueueResponse>> normalResponses =
        new LinkedBlockingQueue<>();
    BlockingQueue<SettableFuture<PollWorkflowTaskQueueResponse>> stickyResponses =
        new LinkedBlockingQueue<>();
    Queue<SettableFuture<PollWorkflowTaskQueueResponse>> allResponses =
        new ConcurrentLinkedQueue<>();
    when(service.futureStub().pollWorkflowTaskQueue(any()))
        .thenAnswer(
            invocation -> {
              PollWorkflowTaskQueueRequest request = invocation.getArgument(0);
              SettableFuture<PollWorkflowTaskQueueResponse> response = SettableFuture.create();
              allResponses.add(response);
              (request.getTaskQueue().getKind() == TaskQueueKind.TASK_QUEUE_KIND_STICKY
                      ? stickyResponses
                      : normalResponses)
                  .add(response);
              return response;
            });
    TrackingSlotSupplier<WorkflowSlotInfo> slots =
        new TrackingSlotSupplier<>(new FixedSizeSlotSupplier<>(4), new NoopScope());
    WorkflowPollerBalancer balancer = new WorkflowPollerBalancer(Optional.of(4), 3);
    AsyncWorkflowPollTask sticky = spy(pollTask(service, slots, true, balancer));
    AsyncWorkflowPollTask normal = spy(pollTask(service, slots, false, balancer));
    CountDownLatch startSticky = new CountDownLatch(1);
    AtomicReference<Thread> normalThread = new AtomicReference<>();
    capturePollThread(normal, normalThread);
    doAnswer(
            invocation -> {
              startSticky.await();
              return invocation.callRealMethod();
            })
        .when(sticky)
        .awaitPollAdmission();
    ShutdownableTaskExecutor<WorkflowTask> executor = mock(ShutdownableTaskExecutor.class);
    doAnswer(
            invocation -> {
              WorkflowTask task = invocation.getArgument(0);
              task.getCompletionCallback().apply(SlotReleaseReason.taskComplete());
              return null;
            })
        .when(executor)
        .process(any());
    AsyncPoller<WorkflowTask> poller =
        new AsyncPoller<>(
            slots,
            new SlotReservationData("queue", "identity", ""),
            Arrays.asList(normal, sticky),
            executor,
            PollerOptions.newBuilder()
                .setPollThreadNamePrefix("sticky-recovery-test")
                .setPollerBehavior(new PollerBehaviorAutoscaling(1, 3, 3))
                .build(),
            new NamespaceCapabilities(),
            new NoopScope());
    poller.start();
    try {
      // Hold back sticky startup so normal polls occupy three of the four shared slots.
      SettableFuture<PollWorkflowTaskQueueResponse> firstNormal = awaitPoll(normalResponses);
      SettableFuture<PollWorkflowTaskQueueResponse> secondNormal = awaitPoll(normalResponses);
      awaitPoll(normalResponses);
      assertEventually(
          Duration.ofSeconds(5),
          () -> {
            verify(normal, times(3)).onPollStarted();
            assertEquals(Thread.State.WAITING, normalThread.get().getState());
            assertEquals(3, slots.getIssuedSlots());
          });
      startSticky.countDown();
      SettableFuture<PollWorkflowTaskQueueResponse> firstSticky = awaitPoll(stickyResponses);
      assertEventually(
          Duration.ofSeconds(5),
          () -> {
            verify(sticky, times(1)).onPollStarted();
            assertEquals(4, slots.getIssuedSlots());
          });

      // Introduce backlog through a real sticky task response, not by changing balancer state.
      firstSticky.set(
          PollWorkflowTaskQueueResponse.newBuilder()
              .setTaskToken(ByteString.copyFromUtf8("sticky-task"))
              .setBacklogCountHint(10)
              .build());
      awaitPoll(stickyResponses);
      verify(executor, timeout(5000)).process(any());

      // Keep one normal poll outstanding. Each released slot must grow the sticky pool,
      // rather than being reclaimed by another normal poll.
      firstNormal.set(PollWorkflowTaskQueueResponse.getDefaultInstance());
      awaitPoll(stickyResponses);
      secondNormal.set(PollWorkflowTaskQueueResponse.getDefaultInstance());
      awaitPoll(stickyResponses);
      assertEventually(
          Duration.ofSeconds(5),
          () -> {
            verify(sticky, times(4)).onPollStarted();
            verify(sticky, times(1)).onPollFinished();
            verify(normal, times(3)).onPollStarted();
            verify(normal, times(2)).onPollFinished();
            assertTrue("Normal polls must not reclaim released slots", normalResponses.isEmpty());
            assertEquals(4, slots.getIssuedSlots());
          });
    } finally {
      startSticky.countDown();
      poller.shutdown(new ShutdownManager(), true).get(5, TimeUnit.SECONDS);
      for (SettableFuture<PollWorkflowTaskQueueResponse> response : allResponses) {
        response.setException(Status.CANCELLED.asRuntimeException());
      }
      poller.awaitTermination(5, TimeUnit.SECONDS);
    }
    assertEventually(Duration.ofSeconds(5), () -> assertEquals(0, slots.getIssuedSlots()));
  }

  @Test(timeout = 20000)
  public void unknownCapacityLeavesRoomForFirstPollOfEitherQueue() throws Exception {
    assertFirstPollAccessWithoutAdvertisedMaximum(true);
    assertFirstPollAccessWithoutAdvertisedMaximum(false);
  }

  private static void assertFirstPollAccessWithoutAdvertisedMaximum(boolean stickyFirst)
      throws Exception {
    WorkflowServiceStubs service = mockService();
    SettableFuture<PollWorkflowTaskQueueResponse> response = SettableFuture.create();
    when(service.futureStub().pollWorkflowTaskQueue(any())).thenReturn(response);
    // The supplier has two real slots but does not advertise that limit to the balancer.
    FixedSizeSlotSupplier<WorkflowSlotInfo> supplier =
        new FixedSizeSlotSupplier<WorkflowSlotInfo>(2) {
          @Override
          public Optional<Integer> getMaximumSlots() {
            return Optional.empty();
          }
        };
    TrackingSlotSupplier<WorkflowSlotInfo> slots =
        new TrackingSlotSupplier<>(supplier, new NoopScope());
    WorkflowPollerBalancer balancer = new WorkflowPollerBalancer(slots.maximumSlots(), 3);
    AsyncWorkflowPollTask first = spy(pollTask(service, slots, stickyFirst, balancer));
    AsyncWorkflowPollTask other = spy(pollTask(service, slots, !stickyFirst, balancer));
    AtomicReference<Thread> firstThread = new AtomicReference<>();
    capturePollThread(first, firstThread);
    CountDownLatch startOther = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              startOther.await();
              return invocation.callRealMethod();
            })
        .when(other)
        .awaitPollAdmission();
    AsyncPoller<WorkflowTask> poller =
        new AsyncPoller<>(
            slots,
            new SlotReservationData("queue", "identity", ""),
            Arrays.asList(first, other),
            mock(ShutdownableTaskExecutor.class),
            PollerOptions.newBuilder()
                .setPollThreadNamePrefix("unknown-capacity-test")
                .setPollerBehavior(new PollerBehaviorAutoscaling(1, 3, 3))
                .build(),
            new NamespaceCapabilities(),
            new NoopScope());
    poller.start();
    try {
      assertEventually(
          Duration.ofSeconds(5),
          () -> {
            verify(first, times(1)).onPollStarted();
            assertEquals(Thread.State.WAITING, firstThread.get().getState());
            assertEquals(
                "Leave a slot for the other queue's first poll", 1, slots.getIssuedSlots());
          });
      startOther.countDown();
      assertEventually(
          Duration.ofSeconds(5),
          () -> {
            verify(first, times(1)).onPollStarted();
            verify(other, times(1)).onPollStarted();
            assertEquals(2, slots.getIssuedSlots());
          });
    } finally {
      startOther.countDown();
      poller.shutdown(new ShutdownManager(), true).get(5, TimeUnit.SECONDS);
      response.setException(Status.CANCELLED.asRuntimeException());
      poller.awaitTermination(5, TimeUnit.SECONDS);
    }
    assertEventually(Duration.ofSeconds(5), () -> assertEquals(0, slots.getIssuedSlots()));
  }

  private static SettableFuture<PollWorkflowTaskQueueResponse> awaitPoll(
      BlockingQueue<SettableFuture<PollWorkflowTaskQueueResponse>> responses)
      throws InterruptedException {
    SettableFuture<PollWorkflowTaskQueueResponse> response = responses.poll(5, TimeUnit.SECONDS);
    assertNotNull("Expected another poll", response);
    return response;
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
