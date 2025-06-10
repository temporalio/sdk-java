package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.internal.BackoffThrottler;
import io.temporal.worker.MetricsType;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseReason;
import io.temporal.worker.tuning.SlotSupplierFuture;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AsyncPoller is a poller that uses a single thread per async task poller. It also supports
 * autoscaling the number of pollers based on the feedback from the poll tasks.
 */
final class AsyncPoller<T extends ScalingTask> extends BasePoller<T> {
  private static final Logger log = LoggerFactory.getLogger(AsyncPoller.class);
  private final TrackingSlotSupplier<?> slotSupplier;
  private final SlotReservationData slotReservationData;
  private final List<PollTaskAsync<T>> asyncTaskPollers;
  private final PollerOptions pollerOptions;
  private final PollerBehaviorAutoscaling pollerBehavior;
  private final Scope workerMetricsScope;
  private Throttler pollRateThrottler;
  private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
      new PollerUncaughtExceptionHandler();
  private final PollQueueBalancer pollerBalancer =
      new PollQueueBalancer(); // Used to balance the number of slots across pollers

  AsyncPoller(
      TrackingSlotSupplier<?> slotSupplier,
      SlotReservationData slotReservationData,
      PollTaskAsync<T> asyncTaskPoller,
      ShutdownableTaskExecutor<T> taskExecutor,
      PollerOptions pollerOptions,
      Scope workerMetricsScope) {
    this(
        slotSupplier,
        slotReservationData,
        Collections.singletonList(asyncTaskPoller),
        taskExecutor,
        pollerOptions,
        workerMetricsScope);
  }

  AsyncPoller(
      TrackingSlotSupplier<?> slotSupplier,
      SlotReservationData slotReservationData,
      List<PollTaskAsync<T>> asyncTaskPollers,
      ShutdownableTaskExecutor<T> taskExecutor,
      PollerOptions pollerOptions,
      Scope workerMetricsScope) {
    super(taskExecutor);
    Objects.requireNonNull(slotSupplier, "slotSupplier cannot be null");
    Objects.requireNonNull(slotReservationData, "slotReservation data should not be null");
    Objects.requireNonNull(asyncTaskPollers, "asyncTaskPollers should not be null");
    if (asyncTaskPollers.isEmpty()) {
      throw new IllegalArgumentException("asyncTaskPollers must contain at least one poller");
    }
    Objects.requireNonNull(pollerOptions, "pollerOptions should not be null");
    Objects.requireNonNull(workerMetricsScope, "workerMetricsScope should not be null");
    this.slotSupplier = slotSupplier;
    this.slotReservationData = slotReservationData;
    this.asyncTaskPollers = asyncTaskPollers;
    if (!(pollerOptions.getPollerBehavior() instanceof PollerBehaviorAutoscaling)) {
      throw new IllegalArgumentException(
          "PollerBehavior "
              + pollerOptions.getPollerBehavior()
              + " is not supported for AsyncPoller. Only PollerBehaviorAutoscaling is supported.");
    }
    this.pollerBehavior = (PollerBehaviorAutoscaling) pollerOptions.getPollerBehavior();
    this.pollerOptions = pollerOptions;
    this.workerMetricsScope = workerMetricsScope;
  }

  @Override
  public boolean start() {
    if (pollerOptions.getMaximumPollRatePerSecond() > 0.0) {
      pollRateThrottler =
          new Throttler(
              "poller",
              pollerOptions.getMaximumPollRatePerSecond(),
              pollerOptions.getMaximumPollRateIntervalMilliseconds());
    }
    // Each poller will have its own thread and one thread will be used to schedule the scale
    // reporters
    ScheduledExecutorService exec =
        Executors.newScheduledThreadPool(
            asyncTaskPollers.size() + 1,
            new ExecutorThreadFactory(
                pollerOptions.getPollThreadNamePrefix(),
                pollerOptions.getUncaughtExceptionHandler()));
    pollExecutor = exec;
    for (PollTaskAsync<T> asyncTaskPoller : asyncTaskPollers) {
      log.info("Starting async poller: {}", asyncTaskPoller.getLabel());
      AdjustableSemaphore pollerSemaphore =
          new AdjustableSemaphore(pollerBehavior.getInitialMaxConcurrentTaskPollers());
      PollScaleReportHandle<T> pollScaleReportHandle =
          new PollScaleReportHandle<>(
              pollerBehavior.getMinConcurrentTaskPollers(),
              pollerBehavior.getMaxConcurrentTaskPollers(),
              pollerBehavior.getInitialMaxConcurrentTaskPollers(),
              (newTarget) -> {
                log.debug(
                    "Updating maximum number of pollers for {} to: {}",
                    asyncTaskPoller.getLabel(),
                    newTarget);
                pollerSemaphore.setMaxPermits(newTarget);
              });
      PollQueueTask pollQueue =
          new PollQueueTask(asyncTaskPoller, pollerSemaphore, pollScaleReportHandle);
      pollerBalancer.addPoller(asyncTaskPoller.getLabel());
      exec.execute(pollQueue);
      exec.scheduleAtFixedRate(pollScaleReportHandle, 0, 100, TimeUnit.MILLISECONDS);
    }
    return true;
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    return super.shutdown(shutdownManager, interruptTasks)
        .thenApply(
            (f) -> {
              for (PollTaskAsync<T> asyncTaskPoller : asyncTaskPollers) {
                try {
                  log.debug("Shutting down async poller: {}", asyncTaskPoller.getLabel());
                  asyncTaskPoller.cancel(new RuntimeException("Shutting down poller"));
                } catch (Throwable e) {
                  log.error("Error while cancelling poll task", e);
                }
              }
              return null;
            });
  }

  public static class PollTaskAsyncAbort extends Exception {
    PollTaskAsyncAbort(String message) {
      super(message);
    }
  }

  public interface PollTaskAsync<TT> {

    CompletableFuture<TT> poll(SlotPermit permit) throws PollTaskAsyncAbort;

    default void cancel(Throwable cause) {
      // no-op
    }

    default String getLabel() {
      return "PollTaskAsync";
    }
  }

  class PollQueueTask implements Runnable {
    private final PollTaskAsync<T> asyncTaskPoller;
    private final PollScaleReportHandle<T> pollScaleReportHandle;
    private final AdjustableSemaphore pollerSemaphore;
    private final BackoffThrottler pollBackoffThrottler;
    private boolean abort = false;

    PollQueueTask(
        PollTaskAsync<T> asyncTaskPoller,
        AdjustableSemaphore pollerSemaphore,
        PollScaleReportHandle<T> pollScaleReportHandle) {
      this.asyncTaskPoller = asyncTaskPoller;
      this.pollBackoffThrottler =
          new BackoffThrottler(
              pollerOptions.getBackoffInitialInterval(),
              pollerOptions.getBackoffCongestionInitialInterval(),
              pollerOptions.getBackoffMaximumInterval(),
              pollerOptions.getBackoffCoefficient(),
              pollerOptions.getBackoffMaximumJitterCoefficient());
      this.pollerSemaphore = pollerSemaphore;
      this.pollScaleReportHandle = pollScaleReportHandle;
    }

    @Override
    public void run() {
      while (!abort) {
        // Permit to reserve a slot for the poll request.
        SlotPermit permit = null;
        // Flag to check if pollerSemaphore was acquired, if so, we need to release it if
        // an exception occurs.
        boolean pollerSemaphoreAcquired = false;
        // Flag to check if poll request was made, if not, we need to release the slot
        // permit and pollerSemaphore in this method.
        boolean pollRequestMade = false;
        try {
          long throttleMs = pollBackoffThrottler.getSleepTime();
          if (throttleMs > 0) {
            Thread.sleep(throttleMs);
          }
          if (pollRateThrottler != null) {
            pollRateThrottler.throttle();
          }

          CountDownLatch suspender = suspendLatch.get();
          if (suspender != null) {
            if (log.isDebugEnabled()) {
              log.debug("poll task suspending latchCount=" + suspender.getCount());
            }
            suspender.await();
          }

          if (shouldTerminate()) {
            return;
          }

          pollerBalancer.balance(asyncTaskPoller.getLabel());
          if (shouldTerminate()) {
            return;
          }
          // Reserve a slot for the poll request
          SlotSupplierFuture future;
          try {
            future = slotSupplier.reserveSlot(slotReservationData);
          } catch (Exception e) {
            log.warn("Error while trying to reserve a slot", e.getCause());
            return;
          }
          permit = BasePoller.getSlotPermitAndHandleInterrupts(future, slotSupplier);
          if (permit == null || shouldTerminate()) {
            return;
          }

          pollerSemaphore.acquire();
          pollerSemaphoreAcquired = true;
          if (shouldTerminate()) {
            return;
          }
          workerMetricsScope.counter(MetricsType.POLLER_START_COUNTER).inc(1);

          SlotPermit finalPermit = permit;
          CompletableFuture<T> pollRequest = asyncTaskPoller.poll(permit);
          // Mark that we have made a poll request
          pollRequestMade = true;
          pollerBalancer.startPoll(asyncTaskPoller.getLabel());

          pollRequest
              .handle(
                  (task, e) -> {
                    pollerBalancer.endPoll(asyncTaskPoller.getLabel());
                    if (e instanceof CompletionException) {
                      e = e.getCause();
                    }
                    pollerSemaphore.release();
                    pollScaleReportHandle.report(task, e);
                    if (e != null) {
                      uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), e);
                      pollBackoffThrottler.failure(
                          (e instanceof StatusRuntimeException)
                              ? ((StatusRuntimeException) e).getStatus().getCode()
                              : Status.Code.UNKNOWN);
                      slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), finalPermit);
                      return null;
                    }
                    if (task != null) {
                      taskExecutor.process(task);
                    } else {
                      slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), finalPermit);
                    }
                    pollBackoffThrottler.success();
                    return null;
                  })
              .exceptionally(
                  throwable -> {
                    log.error("Error while trying to poll task", throwable);
                    return null;
                  });
        } catch (PollTaskAsyncAbort ab) {
          abort = true;
        } catch (Throwable e) {
          if (e instanceof InterruptedException) {
            // we restore the flag here, so it can be checked and processed (with exit) in finally.
            Thread.currentThread().interrupt();
          }
          uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), e);
        } finally {
          // release the slot if it was acquired, but a poll request was not made
          if (!pollRequestMade) {
            if (permit != null) {
              slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), permit);
            }
            if (pollerSemaphoreAcquired) {
              pollerSemaphore.release();
            }
          }

          if (shouldTerminate()) {
            pollerBalancer.removePoller(asyncTaskPoller.getLabel());
            abort = true;
          }
        }
      }
      log.info(
          "Poll loop is terminated: {} - {}",
          AsyncPoller.this.getClass().getSimpleName(),
          asyncTaskPoller.getLabel());
    }
  }

  /**
   * PollQueueBalancer is used to ensure that at least one poller is running for each task. This is
   * necessary to avoid one poller from consuming all the slots and starving other pollers.
   */
  @ThreadSafe
  class PollQueueBalancer {
    Map<String, Integer> taskCounts = new HashMap<>();
    private final Lock balancerLock = new ReentrantLock();
    private final Condition balancerCondition = balancerLock.newCondition();

    void startPoll(String pollerName) {
      balancerLock.lock();
      Integer currentPolls = taskCounts.compute(pollerName, (k, v) -> v + 1);
      if (currentPolls == 1) {
        balancerCondition.signalAll();
      }
      balancerLock.unlock();
    }

    void endPoll(String pollerName) {
      balancerLock.lock();
      if (!taskCounts.containsKey(pollerName)) {
        balancerLock.unlock();
        return;
      }
      Integer currentPolls = taskCounts.compute(pollerName, (k, v) -> v - 1);
      if (currentPolls == 0) {
        balancerCondition.signalAll();
      }
      balancerLock.unlock();
    }

    void addPoller(String pollerName) {
      balancerLock.lock();
      taskCounts.put(pollerName, 0);
      balancerCondition.signalAll();
      balancerLock.unlock();
    }

    void removePoller(String pollerName) {
      balancerLock.lock();
      taskCounts.remove(pollerName);
      balancerCondition.signalAll();
      balancerLock.unlock();
    }

    /** Ensure that at least one poller is running for each task. */
    void balance(String p) throws InterruptedException {
      while (!shouldTerminate()) {
        balancerLock.lock();
        try {
          // If this poller has no tasks then we can unblock immediately
          if (taskCounts.get(p) == 0) {
            return;
          }
          // Check if all tasks have at least one poll request
          boolean allOtherTasksHavePolls = true;
          for (String task : taskCounts.keySet()) {
            if (!Objects.equals(task, p) && taskCounts.get(task) == 0) {
              allOtherTasksHavePolls = false;
              break;
            }
          }
          if (!allOtherTasksHavePolls) {
            balancerCondition.await();
          } else {
            return;
          }
        } finally {
          balancerLock.unlock();
        }
      }
    }
  }
}
