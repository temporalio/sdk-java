package io.temporal.internal.worker;

import io.temporal.worker.tuning.LocalActivitySlotInfo;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseReason;
import io.temporal.worker.tuning.SlotSupplierFuture;
import io.temporal.workflow.Functions;
import java.util.concurrent.*;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LocalActivitySlotSupplierQueue implements Shutdownable {
  static final class QueuedLARequest {
    final boolean isRetry;
    final SlotReservationData data;
    final LocalActivityAttemptTask task;

    QueuedLARequest(boolean isRetry, SlotReservationData data, LocalActivityAttemptTask task) {
      this.isRetry = isRetry;
      this.data = data;
      this.task = task;
    }
  }

  private final PriorityBlockingQueue<QueuedLARequest> requestQueue;
  private final Semaphore newExecutionsBackpressureSemaphore;
  private final TrackingSlotSupplier<LocalActivitySlotInfo> slotSupplier;
  private final Functions.Proc1<LocalActivityAttemptTask> afterReservedCallback;
  private final ExecutorService queueThreadService;
  private static final Logger log =
      LoggerFactory.getLogger(LocalActivitySlotSupplierQueue.class.getName());
  private volatile boolean running = true;
  private volatile boolean wasEverStarted = false;

  LocalActivitySlotSupplierQueue(
      TrackingSlotSupplier<LocalActivitySlotInfo> slotSupplier,
      Functions.Proc1<LocalActivityAttemptTask> afterReservedCallback) {
    this.afterReservedCallback = afterReservedCallback;
    // TODO: See if there's a better option than fixed number for no-max suppliers
    //   https://github.com/temporalio/sdk-java/issues/2149
    int maximumSlots = slotSupplier.maximumSlots().orElse(50) * 2;
    this.newExecutionsBackpressureSemaphore = new Semaphore(maximumSlots);
    this.requestQueue =
        new PriorityBlockingQueue<>(
            maximumSlots,
            (r1, r2) -> {
              // Prioritize retries
              if (r1.isRetry && !r2.isRetry) {
                return -1;
              } else if (!r1.isRetry && r2.isRetry) {
                return 1;
              }
              return 0;
            });
    this.slotSupplier = slotSupplier;
    this.queueThreadService =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "LocalActivitySlotSupplierQueue"));
  }

  private void processQueue() {
    while (running || !requestQueue.isEmpty()) {
      SlotPermit slotPermit = null;
      QueuedLARequest request = null;
      try {
        request = requestQueue.take();

        SlotSupplierFuture future = slotSupplier.reserveSlot(request.data);
        try {
          slotPermit = future.get();
        } catch (InterruptedException e) {
          SlotPermit maybePermitAnyway = future.abortReservation();
          if (maybePermitAnyway != null) {
            slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), maybePermitAnyway);
          }
          Thread.currentThread().interrupt();
          return;
        } catch (ExecutionException e) {
          log.error(
              "Error reserving local activity slot, dropped activity id {}",
              request.task.getActivityId(),
              e);
          continue;
        }

        request.task.getExecutionContext().setPermit(slotPermit);
        afterReservedCallback.apply(request.task);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Throwable e) {
        // Fail the workflow task if something went wrong executing the local activity (at the
        // executor level, otherwise, the LA handler itself should be handling errors)
        log.error("Unexpected error submitting local activity task to worker", e);
        if (slotPermit != null) {
          slotSupplier.releaseSlot(SlotReleaseReason.error(new RuntimeException(e)), slotPermit);
        }
        if (request != null) {
          LocalActivityExecutionContext executionContext = request.task.getExecutionContext();
          executionContext.callback(
              LocalActivityResult.processingFailed(
                  executionContext.getActivityId(), request.task.getAttemptTask().getAttempt(), e));
        }
        if (e.getCause() instanceof InterruptedException) {
          // It's possible the interrupt happens inside the callback, so check that as well.
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  void start() {
    wasEverStarted = true;
    this.queueThreadService.submit(this::processQueue);
  }

  boolean waitOnBackpressure(@Nullable Long acceptanceTimeoutMs) throws InterruptedException {
    boolean accepted;
    if (acceptanceTimeoutMs == null) {
      newExecutionsBackpressureSemaphore.acquire();
      accepted = true;
    } else {
      if (acceptanceTimeoutMs > 0) {
        accepted =
            newExecutionsBackpressureSemaphore.tryAcquire(
                acceptanceTimeoutMs, TimeUnit.MILLISECONDS);
      } else {
        accepted = newExecutionsBackpressureSemaphore.tryAcquire();
      }
    }
    return accepted;
  }

  void submitAttempt(SlotReservationData data, boolean isRetry, LocalActivityAttemptTask task) {
    QueuedLARequest request = new QueuedLARequest(isRetry, data, task);
    requestQueue.add(request);

    if (!isRetry) {
      // If this attempt isn't a retry, that means it had to get a permit from the backpressure
      // semaphore, and therefore we should release that permit now.
      newExecutionsBackpressureSemaphore.release();
    }
  }

  @Override
  public boolean isShutdown() {
    return queueThreadService.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return queueThreadService.isTerminated();
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    running = false;
    // Always interrupt. This won't cause any *tasks* to be interrupted, since the queue thread is
    // only responsible for handing them out.
    queueThreadService.shutdownNow();

    return interruptTasks
        ? shutdownManager.shutdownExecutorNowUntimed(
            queueThreadService, "LocalActivitySlotSupplierQueue")
        : shutdownManager.shutdownExecutorUntimed(
            queueThreadService, "LocalActivitySlotSupplierQueue");
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    if (!wasEverStarted) {
      // Not entirely clear why this is necessary, but await termination will hang the whole
      // timeout duration if no task was ever submitted.
      return;
    }

    ShutdownManager.awaitTermination(queueThreadService, unit.toMillis(timeout));
  }
}
