package io.temporal.internal.worker;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.internal.common.GrpcUtils;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseReason;
import io.temporal.worker.tuning.SlotSupplierFuture;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BasePoller is a base class for pollers that manage the lifecycle of a task executor and a poll
 * executor. It implements the SuspendableWorker interface to provide suspend and resume
 * functionality.
 */
abstract class BasePoller<T> implements SuspendableWorker {
  private static final Logger log = LoggerFactory.getLogger(BasePoller.class);

  protected final AtomicReference<CountDownLatch> suspendLatch = new AtomicReference<>();

  protected TaskExecutor<T> taskExecutor;

  protected ExecutorService pollExecutor;

  protected BasePoller(ShutdownableTaskExecutor<T> taskExecutor) {
    Objects.requireNonNull(taskExecutor, "taskExecutor should not be null");
    this.taskExecutor = taskExecutor;
  }

  @Override
  public abstract boolean start();

  @Override
  public boolean isShutdown() {
    return pollExecutor.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return pollExecutor.isTerminated();
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    log.info("shutdown: {}", this);
    WorkerLifecycleState lifecycleState = getLifecycleState();
    switch (lifecycleState) {
      case NOT_STARTED:
      case TERMINATED:
        return CompletableFuture.completedFuture(null);
    }

    return shutdownManager
        // it's ok to forcefully shutdown pollers, especially because they stuck in a long poll call
        // we don't lose any progress doing that
        .shutdownExecutorNow(pollExecutor, this + "#pollExecutor", Duration.ofSeconds(1))
        .exceptionally(
            e -> {
              log.error("Unexpected exception during shutdown", e);
              return null;
            });
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    WorkerLifecycleState lifecycleState = getLifecycleState();
    switch (lifecycleState) {
      case NOT_STARTED:
      case TERMINATED:
        return;
    }

    long timeoutMillis = unit.toMillis(timeout);
    ShutdownManager.awaitTermination(pollExecutor, timeoutMillis);
  }

  @Override
  public void suspendPolling() {
    if (suspendLatch.compareAndSet(null, new CountDownLatch(1))) {
      log.info("Suspend Polling: {}", this);
    } else {
      log.info("Polling is already suspended: {}", this);
    }
  }

  @Override
  public void resumePolling() {
    CountDownLatch existing = suspendLatch.getAndSet(null);
    if (existing != null) {
      log.info("Resume Polling {}", this);
      existing.countDown();
    }
  }

  @Override
  public boolean isSuspended() {
    return suspendLatch.get() != null;
  }

  @Override
  public WorkerLifecycleState getLifecycleState() {
    if (pollExecutor == null) {
      return WorkerLifecycleState.NOT_STARTED;
    }
    if (suspendLatch.get() != null) {
      return WorkerLifecycleState.SUSPENDED;
    }
    if (pollExecutor.isShutdown()) {
      if (pollExecutor.isTerminated()) {
        return WorkerLifecycleState.TERMINATED;
      } else {
        return WorkerLifecycleState.SHUTDOWN;
      }
    }
    return WorkerLifecycleState.ACTIVE;
  }

  /**
   * Defines if the task should be terminated.
   *
   * <p>This method preserves the interrupted flag of the current thread.
   *
   * @return true if pollExecutor is terminating, or the current thread is interrupted.
   */
  protected boolean shouldTerminate() {
    return pollExecutor.isShutdown() || Thread.currentThread().isInterrupted();
  }

  static SlotPermit getSlotPermitAndHandleInterrupts(
      SlotSupplierFuture future, TrackingSlotSupplier<?> slotSupplier) {
    SlotPermit permit;
    try {
      permit = future.get();
    } catch (InterruptedException e) {
      SlotPermit maybePermitAnyway = future.abortReservation();
      if (maybePermitAnyway != null) {
        slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), maybePermitAnyway);
      }
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException e) {
      log.warn("Error while trying to reserve a slot", e.getCause());
      return null;
    }
    return permit;
  }

  static boolean shouldIgnoreDuringShutdown(Throwable ex) {
    if (ex instanceof StatusRuntimeException) {
      if (GrpcUtils.isChannelShutdownException((StatusRuntimeException) ex)
          || ((StatusRuntimeException) ex).getStatus().getCode().equals(Status.Code.CANCELLED)) {
        return true;
      }
    }
    return
    // if we are terminating and getting rejected execution - it's normal
    ex instanceof RejectedExecutionException
        // if the worker thread gets InterruptedException - it's normal during shutdown
        || ex instanceof InterruptedException
        // if we get wrapped InterruptedException like what PollTask or GRPC clients do with
        // setting Thread.interrupted() on - it's normal during shutdown too. See PollTask
        // javadoc.
        || ex.getCause() instanceof InterruptedException;
  }

  protected final class PollerUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

    @Override
    public void uncaughtException(Thread t, Throwable e) {
      if (!pollExecutor.isShutdown() || !shouldIgnoreDuringShutdown(e)) {
        logPollErrors(t, e);
      } else {
        logPollExceptionsSuppressedDuringShutdown(t, e);
      }
    }

    private void logPollErrors(Thread t, Throwable e) {
      if (e instanceof StatusRuntimeException) {
        StatusRuntimeException te = (StatusRuntimeException) e;
        if (te.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
          log.info("DEADLINE_EXCEEDED in poller thread {}", t.getName(), e);
          return;
        }
      }
      log.warn("Failure in poller thread {}", t.getName(), e);
    }

    /**
     * Some exceptions are considered normal during shutdown {@link #shouldIgnoreDuringShutdown} and
     * we log them in the most quiet manner.
     *
     * @param t thread where the exception happened
     * @param e the exception itself
     */
    private void logPollExceptionsSuppressedDuringShutdown(Thread t, Throwable e) {
      log.trace(
          "Failure in thread {} is suppressed, considered normal during shutdown", t.getName(), e);
    }
  }
}
