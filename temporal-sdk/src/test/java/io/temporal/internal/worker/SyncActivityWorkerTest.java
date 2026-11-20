package io.temporal.internal.worker;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.temporal.client.WorkflowClient;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testUtils.Eventually;
import io.temporal.worker.tuning.FixedSizeSlotSupplier;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class SyncActivityWorkerTest {
  @Test
  public void awaitTerminationWaitsForHeartbeatShutdownContinuation() throws Exception {
    assertWaitsForHeartbeatShutdownContinuation(false);
  }

  @Test
  public void awaitTerminationBeforeShutdownWaitsForHeartbeatShutdownContinuation()
      throws Exception {
    assertWaitsForHeartbeatShutdownContinuation(true);
  }

  private static SyncActivityWorker newIdleWorker() {
    WorkflowClient client = mock(WorkflowClient.class);
    when(client.getWorkflowServiceStubs()).thenReturn(mock(WorkflowServiceStubs.class));
    SyncActivityWorker worker =
        new SyncActivityWorker(
            client,
            "namespace",
            "task-queue",
            0,
            SingleWorkerOptions.newBuilder()
                .setIdentity("identity")
                .setWorkerInterceptors(new WorkerInterceptor[0])
                .setMaxHeartbeatThrottleInterval(Duration.ofSeconds(1))
                .setDefaultHeartbeatThrottleInterval(Duration.ofSeconds(1))
                .build(),
            new FixedSizeSlotSupplier<>(1),
            new NamespaceCapabilities());
    // A workflow-only worker still owns an activity worker, with no registered activity types.
    assertFalse(worker.start());
    return worker;
  }

  private static void assertWaitsForHeartbeatShutdownContinuation(boolean awaitBeforeShutdown)
      throws Exception {
    SyncActivityWorker worker = newIdleWorker();
    CompletableFuture<Boolean> awaitResult = new CompletableFuture<>();
    try (GatedShutdownManager manager = new GatedShutdownManager()) {
      CompletableFuture<Void> shutdown = null;
      if (!awaitBeforeShutdown) {
        shutdown = worker.shutdown(manager, true);
        assertTrue(manager.heartbeatTerminated.await(5, TimeUnit.SECONDS));
        assertFalse(worker.isTerminated());
      }
      Thread waiter =
          new Thread(
              () -> {
                try {
                  worker.awaitTermination(5, TimeUnit.SECONDS);
                  awaitResult.complete(worker.isTerminated());
                } catch (Throwable e) {
                  awaitResult.completeExceptionally(e);
                }
              });
      waiter.start();
      try {
        // Observe the await itself blocking, rather than signalling before it has been entered.
        Eventually.assertEventually(
            Duration.ofSeconds(5),
            () ->
                assertTrue(
                    awaitResult.isDone() || waiter.getState() == Thread.State.TIMED_WAITING));
        assertFalse("awaitTermination returned before activity shutdown", awaitResult.isDone());
        if (awaitBeforeShutdown) {
          shutdown = worker.shutdown(manager, true);
          assertTrue(manager.heartbeatTerminated.await(5, TimeUnit.SECONDS));
          // The heartbeat executor has stopped, but its shutdown continuation is still held.
          Eventually.assertEventually(
              Duration.ofSeconds(5),
              () -> assertTrue(awaitResult.isDone() || isWaitingForIdlePoller(waiter)));
          assertFalse("awaitTermination returned before activity shutdown", awaitResult.isDone());
        }
      } finally {
        manager.continueShutdown.complete(null);
        waiter.join(5_000);
      }
      assertTrue(awaitResult.get(5, TimeUnit.SECONDS));
      shutdown.get(5, TimeUnit.SECONDS);
    }
  }

  private static boolean isWaitingForIdlePoller(Thread waiter) {
    if (waiter.getState() != Thread.State.TIMED_WAITING) {
      return false;
    }
    for (StackTraceElement frame : waiter.getStackTrace()) {
      if (frame.getClassName().equals(NoopWorker.class.getName())
          && frame.getMethodName().equals("awaitTermination")) {
        return true;
      }
    }
    return false;
  }

  private static class GatedShutdownManager extends ShutdownManager {
    private final CompletableFuture<Void> continueShutdown = new CompletableFuture<>();
    private final CountDownLatch heartbeatTerminated = new CountDownLatch(1);

    @Override
    public CompletableFuture<Void> shutdownExecutor(
        ExecutorService executor, String name, Duration timeout) {
      return super.shutdownExecutor(executor, name, timeout)
          .thenCompose(
              ignored -> {
                assertTrue(executor.isTerminated());
                heartbeatTerminated.countDown();
                return continueShutdown;
              });
    }

    @Override
    public void close() {
      continueShutdown.complete(null);
      super.close();
    }
  }
}
