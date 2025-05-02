package io.temporal.internal.worker;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper class that is used instead of null for non initialized worker. This eliminates needs for
 * null checks when calling into it.
 */
class NoopWorker implements SuspendableWorker {

  private final AtomicBoolean shutdown = new AtomicBoolean();

  @Override
  public boolean start() {
    throw new IllegalStateException("Non startable");
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    shutdown.set(true);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {}

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
    return shutdown.get();
  }

  @Override
  public boolean isTerminated() {
    return shutdown.get();
  }

  @Override
  public WorkerLifecycleState getLifecycleState() {
    return WorkerLifecycleState.NOT_STARTED;
  }
}
