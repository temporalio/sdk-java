package io.temporal.internal.worker;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Helper class that is used instead of null for non initialized worker. This eliminates needs for
 * null checks when calling into it.
 */
class NoopWorker implements SuspendableWorker {

  private final CountDownLatch shutdown = new CountDownLatch(1);

  @Override
  public boolean start() {
    throw new IllegalStateException("Non startable");
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    shutdown.countDown();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    try {
      // Shutdown may be initiated by a continuation after another worker component terminates.
      shutdown.await(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void suspendPolling() {}

  @Override
  public void resumePolling() {}

  @Override
  public boolean isSuspended() {
    return true;
  }

  @Override
  public boolean isShutdown() {
    return shutdown.getCount() == 0;
  }

  @Override
  public boolean isTerminated() {
    return shutdown.getCount() == 0;
  }

  @Override
  public WorkerLifecycleState getLifecycleState() {
    return WorkerLifecycleState.NOT_STARTED;
  }
}
