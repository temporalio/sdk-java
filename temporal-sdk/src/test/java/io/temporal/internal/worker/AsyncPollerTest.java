package io.temporal.internal.worker;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.junit.Assert.*;

import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.testUtils.CountingSlotSupplier;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class AsyncPollerTest {

  static class TestScalingTask implements ScalingTask {
    final SlotPermit permit;
    final TrackingSlotSupplier<?> slotSupplier;

    TestScalingTask(SlotPermit permit, TrackingSlotSupplier<?> slotSupplier) {
      this.permit = permit;
      this.slotSupplier = slotSupplier;
    }

    void complete() {
      slotSupplier.releaseSlot(SlotReleaseReason.taskComplete(), permit);
    }

    @Override
    public ScalingDecision getScalingDecision() {
      return null;
    }
  }

  static class DummyTaskExecutor implements ShutdownableTaskExecutor<TestScalingTask> {
    final AtomicInteger processed = new AtomicInteger();
    final AtomicBoolean shutdown = new AtomicBoolean(false);
    final TrackingSlotSupplier<?> slotSupplier;

    DummyTaskExecutor(TrackingSlotSupplier<?> slotSupplier) {
      this.slotSupplier = slotSupplier;
    }

    @Override
    public void process(TestScalingTask task) {
      processed.incrementAndGet();
      task.complete();
    }

    @Override
    public boolean isShutdown() {
      return shutdown.get();
    }

    @Override
    public boolean isTerminated() {
      return shutdown.get();
    }

    @Override
    public CompletableFuture<Void> shutdown(
        ShutdownManager shutdownManager, boolean interruptTasks) {
      shutdown.set(true);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void awaitTermination(long timeout, TimeUnit unit) {}
  }

  static class NoopExecutor extends AbstractExecutorService {
    AtomicBoolean shutdown = new AtomicBoolean(false);

    @Override
    public void shutdown() {
      shutdown.set(true);
    }

    @Override
    public java.util.List<Runnable> shutdownNow() {
      return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
      return shutdown.get();
    }

    @Override
    public boolean isTerminated() {
      return shutdown.get();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public void execute(Runnable command) {
      // no-op
    }
  }

  private AsyncPoller<TestScalingTask> newPoller(
      TrackingSlotSupplier<?> slotSupplier,
      AsyncPoller.PollTaskAsync<TestScalingTask> pollTask,
      DummyTaskExecutor taskExecutor) {
    return newPoller(slotSupplier, pollTask, taskExecutor, new PollerBehaviorAutoscaling(1, 1, 1));
  }

  private AsyncPoller<TestScalingTask> newPoller(
      TrackingSlotSupplier<?> slotSupplier,
      AsyncPoller.PollTaskAsync<TestScalingTask> pollTask,
      DummyTaskExecutor taskExecutor,
      PollerBehavior pollerBehavior) {
    PollerOptions options =
        PollerOptions.newBuilder()
            .setPollThreadNamePrefix("test")
            .setPollerBehavior(pollerBehavior)
            .build();
    return new AsyncPoller<>(
        slotSupplier,
        new SlotReservationData("q", "id", "b"),
        pollTask,
        taskExecutor,
        options,
        new NoopScope());
  }

  private void runOnce(AsyncPoller<TestScalingTask> poller, Runnable task) {
    poller.pollExecutor = new NoopExecutor();
    task.run();
  }

  @Test
  public void testNullTaskReleasesSlot()
      throws InterruptedException, AsyncPoller.PollTaskAsyncAbort {
    CountingSlotSupplier<SlotInfo> slotSupplierInner = new CountingSlotSupplier<>(1);
    TrackingSlotSupplier<?> slotSupplier =
        new TrackingSlotSupplier<>(slotSupplierInner, new NoopScope());
    DummyTaskExecutor executor = new DummyTaskExecutor(slotSupplier);

    AsyncPoller.PollTaskAsync<TestScalingTask> pollTask =
        Mockito.mock(AsyncPoller.PollTaskAsync.class);
    CountDownLatch pollLatch = new CountDownLatch(1);
    Mockito.when(pollTask.poll(Mockito.any()))
        .then(
            i -> {
              pollLatch.countDown();
              return CompletableFuture.completedFuture(null);
            });

    AsyncPoller<TestScalingTask> poller = newPoller(slotSupplier, pollTask, executor);

    assertTrue(poller.start());
    pollLatch.await();

    assertEventually(
        Duration.ofSeconds(5),
        () -> {
          assertEquals(0, executor.processed.get());
          assertTrue(slotSupplierInner.reservedCount.get() > 1);
          assertEquals(0, slotSupplier.getUsedSlots().size());
        });
  }

  @Test
  public void testSlots() throws InterruptedException, AsyncPoller.PollTaskAsyncAbort {
    CountingSlotSupplier<SlotInfo> slotSupplierInner = new CountingSlotSupplier<>(5);
    TrackingSlotSupplier<?> slotSupplier =
        new TrackingSlotSupplier<>(slotSupplierInner, new NoopScope());
    DummyTaskExecutor executor = new DummyTaskExecutor(slotSupplier);

    AsyncPoller.PollTaskAsync<TestScalingTask> pollTask =
        Mockito.mock(AsyncPoller.PollTaskAsync.class);
    CountDownLatch pollLatch = new CountDownLatch(5);
    CompletableFuture future = new CompletableFuture<TestScalingTask>();
    Mockito.when(pollTask.poll(Mockito.any()))
        .then(
            i -> {
              if (pollLatch.getCount() == 0) {
                return new CompletableFuture<TestScalingTask>();
              }
              pollLatch.countDown();
              return future;
            });

    AsyncPoller<TestScalingTask> poller =
        newPoller(slotSupplier, pollTask, executor, new PollerBehaviorAutoscaling(10, 10, 10));

    assertTrue(poller.start());
    pollLatch.await();

    assertEventually(
        Duration.ofSeconds(1),
        () -> {
          assertEquals(5, slotSupplierInner.reservedCount.get());
          assertEquals(0, slotSupplier.getUsedSlots().size());
        });

    // TODO don't use a null permit
    future.complete(new TestScalingTask(null, slotSupplier));

    assertEventually(
        Duration.ofSeconds(1),
        () -> {
          assertEquals(5, executor.processed.get());
        });
  }

  @Test
  public void testShutdownOnBlockedPolls()
      throws InterruptedException, ExecutionException, AsyncPoller.PollTaskAsyncAbort {
    CountingSlotSupplier<SlotInfo> slotSupplierInner = new CountingSlotSupplier<>(5);
    TrackingSlotSupplier<?> slotSupplier =
        new TrackingSlotSupplier<>(slotSupplierInner, new NoopScope());
    DummyTaskExecutor executor = new DummyTaskExecutor(slotSupplier);

    AsyncPoller.PollTaskAsync<TestScalingTask> pollTask =
        Mockito.mock(AsyncPoller.PollTaskAsync.class);
    CountDownLatch pollLatch = new CountDownLatch(5);
    CompletableFuture future = new CompletableFuture<TestScalingTask>();
    Mockito.when(pollTask.poll(Mockito.any()))
        .then(
            i -> {
              pollLatch.countDown();
              return future;
            });

    AsyncPoller<TestScalingTask> poller =
        newPoller(slotSupplier, pollTask, executor, new PollerBehaviorAutoscaling(10, 10, 10));

    assertTrue(poller.start());
    pollLatch.await();

    poller.shutdown(new ShutdownManager(), false).get();
    // TODO This should not be needed
    executor.shutdown(new ShutdownManager(), false).get();
    future.completeExceptionally(new StatusRuntimeException(Status.CANCELLED));

    poller.awaitTermination(1, TimeUnit.SECONDS);

    assertTrue(poller.isTerminated());
    assertEventually(
        Duration.ofSeconds(5),
        () -> {
          assertEquals(0, executor.processed.get());
          assertEquals(0, slotSupplier.getUsedSlots().size());
          assertEquals(0, slotSupplier.getIssuedSlots());
        });
  }

  @Test
  public void testFailingPoll()
      throws InterruptedException, ExecutionException, AsyncPoller.PollTaskAsyncAbort {
    CountingSlotSupplier<SlotInfo> slotSupplierInner = new CountingSlotSupplier<>(1);
    TrackingSlotSupplier<?> slotSupplier =
        new TrackingSlotSupplier<>(slotSupplierInner, new NoopScope());
    DummyTaskExecutor executor = new DummyTaskExecutor(slotSupplier);

    AsyncPoller.PollTaskAsync<TestScalingTask> pollTask =
        Mockito.mock(AsyncPoller.PollTaskAsync.class);
    CompletableFuture firstPoll = new CompletableFuture<TestScalingTask>();
    Mockito.when(pollTask.poll(Mockito.any()))
        .then(i -> firstPoll)
        .then(i -> new CompletableFuture<TestScalingTask>());

    AsyncPoller<TestScalingTask> poller =
        newPoller(slotSupplier, pollTask, executor, new PollerBehaviorAutoscaling(10, 10, 10));

    assertTrue(poller.start());
    // Fail the first poll to simulate a poll failure
    firstPoll.completeExceptionally(new StatusRuntimeException(Status.UNAVAILABLE));

    assertEventually(
        Duration.ofSeconds(5),
        () -> {
          assertEquals(0, executor.processed.get());
          assertEquals(2, slotSupplierInner.reservedCount.get());
          assertEquals(1, slotSupplierInner.releasedCount.get());
          assertEquals(0, slotSupplier.getUsedSlots().size());
        });

    poller.shutdown(new ShutdownManager(), false).get();
    poller.awaitTermination(1, TimeUnit.SECONDS);
    Assert.assertTrue(poller.isShutdown());
  }

  @Test
  public void testAsyncPollFailed()
      throws InterruptedException, ExecutionException, AsyncPoller.PollTaskAsyncAbort {
    CountingSlotSupplier<SlotInfo> slotSupplierInner = new CountingSlotSupplier<>(1);
    TrackingSlotSupplier<?> slotSupplier =
        new TrackingSlotSupplier<>(slotSupplierInner, new NoopScope());
    DummyTaskExecutor executor = new DummyTaskExecutor(slotSupplier);

    AsyncPoller.PollTaskAsync<TestScalingTask> pollTask =
        Mockito.mock(AsyncPoller.PollTaskAsync.class);
    Mockito.when(pollTask.poll(Mockito.any()))
        .thenThrow(new RuntimeException("Poll failed"))
        .then(
            (i) ->
                CompletableFuture.completedFuture(
                    new TestScalingTask(i.getArgument(0), slotSupplier)))
        .thenReturn(new CompletableFuture<>());

    AsyncPoller<TestScalingTask> poller =
        newPoller(slotSupplier, pollTask, executor, new PollerBehaviorAutoscaling(10, 10, 10));

    assertTrue(poller.start());

    assertEventually(
        Duration.ofSeconds(5),
        () -> {
          assertEquals(1, executor.processed.get());
          assertEquals(3, slotSupplierInner.reservedCount.get());
          assertEquals(2, slotSupplierInner.releasedCount.get());
          assertEquals(0, slotSupplier.getUsedSlots().size());
        });

    poller.shutdown(new ShutdownManager(), false).get();
    poller.awaitTermination(1, TimeUnit.SECONDS);
    Assert.assertTrue(poller.isShutdown());
  }

  @Test
  public void testSuspendPolling()
      throws InterruptedException, ExecutionException, AsyncPoller.PollTaskAsyncAbort {
    CountingSlotSupplier<SlotInfo> slotSupplierInner = new CountingSlotSupplier<>(1);
    TrackingSlotSupplier<?> slotSupplier =
        new TrackingSlotSupplier<>(slotSupplierInner, new NoopScope());
    DummyTaskExecutor executor = new DummyTaskExecutor(slotSupplier);

    AsyncPoller.PollTaskAsync<TestScalingTask> pollTask =
        Mockito.mock(AsyncPoller.PollTaskAsync.class);
    AtomicReference<Functions.Proc> completePoll = new AtomicReference<>();
    CountDownLatch pollLatch = new CountDownLatch(1);
    Mockito.when(pollTask.poll(Mockito.any()))
        .then(
            i -> {
              CompletableFuture future = new CompletableFuture<TestScalingTask>();
              SlotPermit permit = i.getArgument(0);
              completePoll.set(() -> future.complete(new TestScalingTask(permit, slotSupplier)));
              pollLatch.countDown();
              return future;
            })
        .then(i -> new CompletableFuture<TestScalingTask>());

    AsyncPoller<TestScalingTask> poller = newPoller(slotSupplier, pollTask, executor);

    // Suspend polling
    poller.suspendPolling();
    assertTrue(poller.isSuspended());
    // Start the worker with polling suspended
    assertTrue(poller.start());
    // Since polling is suspended, we shouldn't have even issued any slots or poll requests
    assertEquals(0, slotSupplier.getIssuedSlots());
    assertEquals(1, pollLatch.getCount());
    // Resume polling
    poller.resumePolling();
    assertFalse(poller.isSuspended());
    pollLatch.await();
    assertEventually(
        Duration.ofSeconds(1),
        () -> {
          assertEquals(0, executor.processed.get());
          assertEquals(1, slotSupplierInner.reservedCount.get());
          assertEquals(0, slotSupplier.getUsedSlots().size());
        });
    // Suspend polling again, this will not affect the already issued poll request
    poller.suspendPolling();
    completePoll.get().apply();
    assertEventually(
        Duration.ofSeconds(1),
        () -> {
          assertEquals(1, executor.processed.get());
          assertEquals(2, slotSupplierInner.reservedCount.get());
          assertEquals(1, slotSupplierInner.releasedCount.get());
          assertEquals(0, slotSupplier.getUsedSlots().size());
        });

    poller.shutdown(new ShutdownManager(), false).get();
    poller.awaitTermination(1, TimeUnit.SECONDS);
    Assert.assertTrue(poller.isShutdown());
  }
}
