package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.internal.BackoffThrottler;
import io.temporal.internal.task.VirtualThreadDelegate;
import io.temporal.worker.MetricsType;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MultiThreadedPoller is a poller that uses multiple threads to poll tasks. It uses one thread per
 * poll request.
 */
final class MultiThreadedPoller<T> extends BasePoller<T> {

  public interface PollTask<TT> {
    /**
     * Pollers should shade or wrap all {@code java.lang.InterruptedException}s and raise {@code
     * Thread.interrupted()} flag. This follows GRPC stubs approach, see {@code
     * io.grpc.stub.ClientCalls#blockingUnaryCall}. Because pollers use GRPC stubs anyway, we chose
     * this implementation for consistency. The caller of the poll task is responsible for handling
     * the flag.
     *
     * @return result of the task
     */
    TT poll();
  }

  interface ThrowingRunnable {
    void run() throws Throwable;
  }

  private static final Logger log = LoggerFactory.getLogger(MultiThreadedPoller.class);
  private final String identity;
  private final PollTask<T> pollTask;
  private final PollerOptions pollerOptions;
  private final Scope workerMetricsScope;

  private Throttler pollRateThrottler;

  private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
      new PollerUncaughtExceptionHandler();

  public MultiThreadedPoller(
      String identity,
      PollTask<T> pollTask,
      ShutdownableTaskExecutor<T> taskExecutor,
      PollerOptions pollerOptions,
      Scope workerMetricsScope) {
    super(taskExecutor);
    Objects.requireNonNull(identity, "identity cannot be null");
    Objects.requireNonNull(pollTask, "poll service should not be null");
    Objects.requireNonNull(pollerOptions, "pollerOptions should not be null");
    Objects.requireNonNull(workerMetricsScope, "workerMetricsScope should not be null");

    this.identity = identity;
    this.pollTask = pollTask;
    this.pollerOptions = pollerOptions;
    this.workerMetricsScope = workerMetricsScope;
  }

  @Override
  public boolean start() {
    log.info("start: {}", this);

    if (pollerOptions.getMaximumPollRatePerSecond() > 0.0) {
      pollRateThrottler =
          new Throttler(
              "poller",
              pollerOptions.getMaximumPollRatePerSecond(),
              pollerOptions.getMaximumPollRateIntervalMilliseconds());
    }

    if (!(pollerOptions.getPollerBehavior() instanceof PollerBehaviorSimpleMaximum)) {
      throw new IllegalArgumentException(
          "PollerBehavior "
              + pollerOptions.getPollerBehavior()
              + " is not supported. Only PollerBehaviorSimpleMaximum is supported.");
    }
    PollerBehaviorSimpleMaximum pollerBehavior =
        (PollerBehaviorSimpleMaximum) pollerOptions.getPollerBehavior();

    // If virtual threads are enabled, we use a virtual thread executor.
    if (pollerOptions.isUsingVirtualThreads()) {
      AtomicInteger threadIndex = new AtomicInteger();
      pollExecutor =
          VirtualThreadDelegate.newVirtualThreadExecutor(
              (t) -> {
                // TODO: Consider using a more descriptive name for the thread.
                t.setName(
                    pollerOptions.getPollThreadNamePrefix() + ": " + threadIndex.incrementAndGet());
                t.setUncaughtExceptionHandler(uncaughtExceptionHandler);
              });
    } else {
      // It is important to pass blocking queue of at least options.getPollThreadCount() capacity.
      // As task enqueues next task the buffering is needed to queue task until the previous one
      // releases a thread.
      ThreadPoolExecutor threadPoolPoller =
          new ThreadPoolExecutor(
              pollerBehavior.getMaxConcurrentTaskPollers(),
              pollerBehavior.getMaxConcurrentTaskPollers(),
              1,
              TimeUnit.SECONDS,
              new ArrayBlockingQueue<>(pollerBehavior.getMaxConcurrentTaskPollers()));
      threadPoolPoller.setThreadFactory(
          new ExecutorThreadFactory(
              pollerOptions.getPollThreadNamePrefix(),
              pollerOptions.getUncaughtExceptionHandler()));
      pollExecutor = threadPoolPoller;
    }

    for (int i = 0; i < pollerBehavior.getMaxConcurrentTaskPollers(); i++) {
      pollExecutor.execute(new PollLoopTask(new PollExecutionTask()));
      workerMetricsScope.counter(MetricsType.POLLER_START_COUNTER).inc(1);
    }

    return true;
  }

  @Override
  public String toString() {
    // TODO using pollThreadNamePrefix here is ugly. We should consider introducing some concept of
    // WorkerContext [workerIdentity, namespace, queue, local/non-local if applicable] and pass it
    // around
    // that will simplify such kind of logging through workers.
    return String.format(
        "MultiThreadedPoller{name=%s, identity=%s}",
        pollerOptions.getPollThreadNamePrefix(), identity);
  }

  private class PollLoopTask implements Runnable {

    private final MultiThreadedPoller.ThrowingRunnable task;
    private final BackoffThrottler pollBackoffThrottler;

    PollLoopTask(MultiThreadedPoller.ThrowingRunnable task) {
      this.task = task;
      this.pollBackoffThrottler =
          new BackoffThrottler(
              pollerOptions.getBackoffInitialInterval(),
              pollerOptions.getBackoffCongestionInitialInterval(),
              pollerOptions.getBackoffMaximumInterval(),
              pollerOptions.getBackoffCoefficient(),
              pollerOptions.getBackoffMaximumJitterCoefficient());
    }

    @Override
    public void run() {
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

        task.run();
        pollBackoffThrottler.success();
      } catch (Throwable e) {
        if (e instanceof InterruptedException) {
          // we restore the flag here, so it can be checked and processed (with exit) in finally.
          Thread.currentThread().interrupt();
        } else {
          // Don't increase throttle on InterruptedException
          pollBackoffThrottler.failure(
              (e instanceof StatusRuntimeException)
                  ? ((StatusRuntimeException) e).getStatus().getCode()
                  : Status.Code.UNKNOWN);
        }
        uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), e);
      } finally {
        if (!shouldTerminate()) {
          // Resubmit itself back to pollExecutor
          pollExecutor.execute(this);
        } else {
          log.info(
              "poll loop is terminated: {}",
              MultiThreadedPoller.this.pollTask.getClass().getSimpleName());
        }
      }
    }
  }

  private class PollExecutionTask implements MultiThreadedPoller.ThrowingRunnable {

    @Override
    public void run() throws Exception {
      T task = pollTask.poll();
      if (task != null) {
        taskExecutor.process(task);
      }
    }
  }
}
