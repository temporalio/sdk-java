package io.temporal.internal.worker;

import static io.temporal.testUtils.Eventually.assertEventually;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.namespace.v1.NamespaceInfo;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollNexusTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.ShutdownWorkerRequest;
import io.temporal.api.workflowservice.v1.ShutdownWorkerResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.tuning.FixedSizeSlotSupplier;
import io.temporal.worker.tuning.PollerBehavior;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;

/**
 * An autoscaling poller pool reports its current target on every poll request; a fixed-size pool
 * reports nothing at all.
 */
public class PollerPoolInfoReportingTest {

  private final List<SettableFuture<?>> unanswered =
      Collections.synchronizedList(new ArrayList<>());
  private final CountDownLatch releaseBlockedPolls = new CountDownLatch(1);

  @After
  public void releaseOutstandingPolls() {
    releaseBlockedPolls.countDown();
    synchronized (unanswered) {
      for (SettableFuture<?> poll : unanswered) {
        poll.setException(new StatusRuntimeException(Status.CANCELLED));
      }
    }
  }

  @Test
  public void activityPollRequestCarriesTheTarget() throws Exception {
    Service service = new Service();
    List<PollActivityTaskQueueRequest> requests = recorded();
    CountDownLatch polled = new CountDownLatch(1);
    when(service.futureStub.pollActivityTaskQueue(any()))
        .thenAnswer(i -> hold(i, requests, polled));

    ActivityWorker worker =
        activityWorker(
            service, new PollerBehaviorAutoscaling(1, 10, 4), new NamespaceCapabilities());
    try {
      assertTrue(worker.start());
      assertTrue(polled.await(10, SECONDS));
      assertEquals(4, requests.get(0).getPollerPoolInfo().getTargetPollers());
    } finally {
      shutdown(worker);
    }
  }

  @Test
  public void workflowPollRequestCarriesTheTarget() throws Exception {
    Service service = new Service();
    List<PollWorkflowTaskQueueRequest> requests = recorded();
    CountDownLatch polled = new CountDownLatch(1);
    when(service.futureStub.pollWorkflowTaskQueue(any()))
        .thenAnswer(i -> hold(i, requests, polled));

    WorkflowWorker worker =
        workflowWorker(
            service, new PollerBehaviorAutoscaling(1, 10, 4), new NamespaceCapabilities());
    try {
      assertTrue(worker.start());
      assertTrue(polled.await(10, SECONDS));
      assertEquals(4, requests.get(0).getPollerPoolInfo().getTargetPollers());
    } finally {
      shutdown(worker);
    }
  }

  @Test
  public void nexusPollRequestCarriesTheTarget() throws Exception {
    Service service = new Service();
    List<PollNexusTaskQueueRequest> requests = recorded();
    CountDownLatch polled = new CountDownLatch(1);
    when(service.futureStub.pollNexusTaskQueue(any())).thenAnswer(i -> hold(i, requests, polled));

    NexusWorker worker =
        nexusWorker(service, new PollerBehaviorAutoscaling(1, 10, 4), new NamespaceCapabilities());
    try {
      assertTrue(worker.start());
      assertTrue(polled.await(10, SECONDS));
      assertEquals(4, requests.get(0).getPollerPoolInfo().getTargetPollers());
    } finally {
      shutdown(worker);
    }
  }

  @Test
  public void activityPollTargetFollowsTheAutoscaler() throws Exception {
    Service service = new Service();
    List<PollActivityTaskQueueRequest> requests = recorded();
    when(service.futureStub.pollActivityTaskQueue(any())).thenAnswer(i -> fail(i, requests));

    ActivityWorker worker =
        activityWorker(service, new PollerBehaviorAutoscaling(1, 10, 5), autoscalingNamespace());
    try {
      assertTrue(worker.start());
      // Each failed poll scales the pool down by one until it bottoms out at the configured
      // minimum, so the target the requests carry has to move away from its initial value.
      assertEventually(
          Duration.ofSeconds(10),
          () -> {
            assertFalse(requests.isEmpty());
            assertEquals(5, requests.get(0).getPollerPoolInfo().getTargetPollers());
            assertEquals(1, last(requests).getPollerPoolInfo().getTargetPollers());
          });
    } finally {
      shutdown(worker);
    }
  }

  @Test
  public void workflowPollTargetFollowsTheAutoscaler() throws Exception {
    Service service = new Service();
    List<PollWorkflowTaskQueueRequest> requests = recorded();
    when(service.futureStub.pollWorkflowTaskQueue(any())).thenAnswer(i -> fail(i, requests));

    WorkflowWorker worker =
        workflowWorker(service, new PollerBehaviorAutoscaling(1, 10, 5), autoscalingNamespace());
    try {
      assertTrue(worker.start());
      assertEventually(
          Duration.ofSeconds(10),
          () -> {
            assertFalse(requests.isEmpty());
            assertEquals(5, requests.get(0).getPollerPoolInfo().getTargetPollers());
            assertEquals(1, last(requests).getPollerPoolInfo().getTargetPollers());
          });
    } finally {
      shutdown(worker);
    }
  }

  @Test
  public void fixedSizeActivityPoolReportsNothing() throws Exception {
    Service service = new Service();
    List<PollActivityTaskQueueRequest> requests = recorded();
    CountDownLatch polled = new CountDownLatch(1);
    when(service.blockingStub.pollActivityTaskQueue(any()))
        .thenAnswer(i -> block(i, requests, polled));

    ActivityWorker worker =
        activityWorker(service, new PollerBehaviorSimpleMaximum(1), new NamespaceCapabilities());
    try {
      assertTrue(worker.start());
      assertTrue(polled.await(10, SECONDS));
      assertFalse(requests.get(0).hasPollerPoolInfo());
    } finally {
      shutdown(worker);
    }
  }

  @Test
  public void fixedSizeWorkflowPoolReportsNothing() throws Exception {
    Service service = new Service();
    List<PollWorkflowTaskQueueRequest> requests = recorded();
    CountDownLatch polled = new CountDownLatch(1);
    when(service.blockingStub.pollWorkflowTaskQueue(any()))
        .thenAnswer(i -> block(i, requests, polled));

    WorkflowWorker worker =
        workflowWorker(service, new PollerBehaviorSimpleMaximum(1), new NamespaceCapabilities());
    try {
      assertTrue(worker.start());
      assertTrue(polled.await(10, SECONDS));
      assertFalse(requests.get(0).hasPollerPoolInfo());
    } finally {
      shutdown(worker);
    }
  }

  @Test
  public void fixedSizeNexusPoolReportsNothing() throws Exception {
    Service service = new Service();
    List<PollNexusTaskQueueRequest> requests = recorded();
    CountDownLatch polled = new CountDownLatch(1);
    when(service.blockingStub.pollNexusTaskQueue(any()))
        .thenAnswer(i -> block(i, requests, polled));

    NexusWorker worker =
        nexusWorker(service, new PollerBehaviorSimpleMaximum(1), new NamespaceCapabilities());
    try {
      assertTrue(worker.start());
      assertTrue(polled.await(10, SECONDS));
      assertFalse(requests.get(0).hasPollerPoolInfo());
    } finally {
      shutdown(worker);
    }
  }

  private static <T> List<T> recorded() {
    return Collections.synchronizedList(new ArrayList<>());
  }

  private static <T> T last(List<T> requests) {
    synchronized (requests) {
      return requests.get(requests.size() - 1);
    }
  }

  /** Records the request and leaves the poll outstanding, as a long poll with no task would. */
  private <REQ, RESP> ListenableFuture<RESP> hold(
      InvocationOnMock invocation, List<REQ> requests, CountDownLatch polled) {
    requests.add(invocation.getArgument(0));
    polled.countDown();
    SettableFuture<RESP> poll = SettableFuture.create();
    unanswered.add(poll);
    return poll;
  }

  /** Records the request and fails the poll, which scales the pool down by one. */
  private static <REQ, RESP> ListenableFuture<RESP> fail(
      InvocationOnMock invocation, List<REQ> requests) {
    requests.add(invocation.getArgument(0));
    return Futures.immediateFailedFuture(new StatusRuntimeException(Status.UNAVAILABLE));
  }

  /** The blocking-stub equivalent of {@link #hold}. */
  private <REQ, RESP> RESP block(
      InvocationOnMock invocation, List<REQ> requests, CountDownLatch polled) throws Exception {
    requests.add(invocation.getArgument(0));
    polled.countDown();
    releaseBlockedPolls.await();
    return null;
  }

  private static NamespaceCapabilities autoscalingNamespace() {
    NamespaceCapabilities capabilities = new NamespaceCapabilities();
    capabilities.setFromCapabilities(
        NamespaceInfo.Capabilities.newBuilder().setPollerAutoscaling(true).build());
    return capabilities;
  }

  private static void shutdown(SuspendableWorker worker) throws Exception {
    try (ShutdownManager shutdownManager = new ShutdownManager()) {
      worker.shutdown(shutdownManager, true).get(10, SECONDS);
    }
  }

  private static ActivityWorker activityWorker(
      Service service, PollerBehavior behavior, NamespaceCapabilities capabilities) {
    ActivityTaskHandler handler = mock(ActivityTaskHandler.class);
    when(handler.isAnyTypeSupported()).thenReturn(true);
    return new ActivityWorker(
        service.stubs,
        "default",
        "task_queue",
        1.0,
        workerOptions(behavior),
        handler,
        new FixedSizeSlotSupplier<>(10),
        capabilities);
  }

  private static WorkflowWorker workflowWorker(
      Service service, PollerBehavior behavior, NamespaceCapabilities capabilities) {
    WorkflowTaskHandler handler = mock(WorkflowTaskHandler.class);
    when(handler.isAnyTypeSupported()).thenReturn(true);
    WorkflowRunLockManager runLocks = new WorkflowRunLockManager();
    return new WorkflowWorker(
        service.stubs,
        "default",
        "task_queue",
        "sticky_task_queue",
        workerOptions(behavior),
        runLocks,
        new WorkflowExecutorCache(10, runLocks, new NoopScope()),
        handler,
        mock(EagerActivityDispatcher.class),
        3,
        new FixedSizeSlotSupplier<>(10),
        capabilities);
  }

  private static NexusWorker nexusWorker(
      Service service, PollerBehavior behavior, NamespaceCapabilities capabilities) {
    NexusTaskHandler handler = mock(NexusTaskHandler.class);
    when(handler.start()).thenReturn(true);
    return new NexusWorker(
        service.stubs,
        "default",
        "task_queue",
        workerOptions(behavior),
        handler,
        DefaultDataConverter.newDefaultInstance(),
        new FixedSizeSlotSupplier<>(10),
        capabilities);
  }

  private static SingleWorkerOptions workerOptions(PollerBehavior behavior) {
    return SingleWorkerOptions.newBuilder()
        .setIdentity("test_identity")
        .setBuildId(UUID.randomUUID().toString())
        .setWorkerInstanceKey(UUID.randomUUID().toString())
        .setPollerOptions(
            PollerOptions.newBuilder()
                .setPollerBehavior(behavior)
                // Keep the retries after a failed poll tight so the scale-down is quick.
                .setBackoffInitialInterval(Duration.ofMillis(1))
                .setBackoffCongestionInitialInterval(Duration.ofMillis(1))
                .setBackoffMaximumInterval(Duration.ofMillis(10))
                .setUncaughtExceptionHandler((thread, error) -> {})
                .build())
        .setMetricsScope(new NoopScope())
        .build();
  }

  private static final class Service {
    final WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    final WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub =
        mock(WorkflowServiceGrpc.WorkflowServiceFutureStub.class);
    final WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);

    @SuppressWarnings("deprecation")
    Service() {
      when(stubs.getServerCapabilities())
          .thenReturn(() -> GetSystemInfoResponse.Capabilities.getDefaultInstance());
      when(stubs.futureStub()).thenReturn(futureStub);
      when(stubs.blockingStub()).thenReturn(blockingStub);
      when(futureStub.withOption(any(), any())).thenReturn(futureStub);
      when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);
      when(futureStub.shutdownWorker(any(ShutdownWorkerRequest.class)))
          .thenReturn(Futures.immediateFuture(ShutdownWorkerResponse.getDefaultInstance()));
    }
  }
}
