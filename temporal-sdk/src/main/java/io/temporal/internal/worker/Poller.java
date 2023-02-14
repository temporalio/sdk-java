/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.internal.BackoffThrottler;
import io.temporal.internal.common.GrpcUtils;
import io.temporal.worker.MetricsType;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Poller<T> implements SuspendableWorker {

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

  private final String identity;
  private final ShutdownableTaskExecutor<T> taskExecutor;
  private final PollTask<T> pollTask;
  private final PollerOptions pollerOptions;
  private static final Logger log = LoggerFactory.getLogger(Poller.class);
  private ThreadPoolExecutor pollExecutor;
  private final Scope workerMetricsScope;

  private final AtomicReference<CountDownLatch> suspendLatch = new AtomicReference<>();

  private Throttler pollRateThrottler;

  private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
      new PollerUncaughtExceptionHandler();

  public Poller(
      String identity,
      PollTask<T> pollTask,
      ShutdownableTaskExecutor<T> taskExecutor,
      PollerOptions pollerOptions,
      Scope workerMetricsScope) {
    Objects.requireNonNull(identity, "identity cannot be null");
    Objects.requireNonNull(pollTask, "poll service should not be null");
    Objects.requireNonNull(taskExecutor, "taskExecutor should not be null");
    Objects.requireNonNull(pollerOptions, "pollerOptions should not be null");
    Objects.requireNonNull(workerMetricsScope, "workerMetricsScope should not be null");

    this.identity = identity;
    this.pollTask = pollTask;
    this.taskExecutor = taskExecutor;
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

    // It is important to pass blocking queue of at least options.getPollThreadCount() capacity. As
    // task enqueues next task the buffering is needed to queue task until the previous one releases
    // a thread.
    pollExecutor =
        new ThreadPoolExecutor(
            pollerOptions.getPollThreadCount(),
            pollerOptions.getPollThreadCount(),
            1,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(pollerOptions.getPollThreadCount()));
    pollExecutor.setThreadFactory(
        new ExecutorThreadFactory(
            pollerOptions.getPollThreadNamePrefix(), pollerOptions.getUncaughtExceptionHandler()));

    for (int i = 0; i < pollerOptions.getPollThreadCount(); i++) {
      pollExecutor.execute(new PollLoopTask(new PollExecutionTask()));
      workerMetricsScope.counter(MetricsType.POLLER_START_COUNTER).inc(1);
    }

    return true;
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
        // TODO Poller shouldn't shutdown taskExecutor, because it gets it already created
        // externally.
        //  Creator of taskExecutor should be responsible for it's shutdown
        .thenCompose(ignore -> taskExecutor.shutdown(shutdownManager, interruptTasks))
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
    timeoutMillis = ShutdownManager.awaitTermination(pollExecutor, timeoutMillis);
    ShutdownManager.awaitTermination(taskExecutor, timeoutMillis);
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
  public boolean isShutdown() {
    return pollExecutor.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return pollExecutor.isTerminated() && taskExecutor.isTerminated();
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
      // return TERMINATED only if both pollExecutor and taskExecutor are terminated
      if (pollExecutor.isTerminated() && taskExecutor.isTerminated()) {
        return WorkerLifecycleState.TERMINATED;
      } else {
        return WorkerLifecycleState.SHUTDOWN;
      }
    }
    return WorkerLifecycleState.ACTIVE;
  }

  @Override
  public String toString() {
    // TODO using pollThreadNamePrefix here is ugly. We should consider introducing some concept of
    // WorkerContext [workerIdentity, namespace, queue, local/non-local if applicable] and pass it
    // around
    // that will simplify such kind of logging through workers.
    return String.format(
        "Poller{name=%s, identity=%s}", pollerOptions.getPollThreadNamePrefix(), identity);
  }

  private class PollLoopTask implements Runnable {

    private final Poller.ThrowingRunnable task;
    private final BackoffThrottler pollBackoffThrottler;

    PollLoopTask(Poller.ThrowingRunnable task) {
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

        CountDownLatch suspender = Poller.this.suspendLatch.get();
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
          log.info("poll loop is terminated: {}", Poller.this.pollTask.getClass().getSimpleName());
        }
      }
    }

    /**
     * Defines if the task should be terminated.
     *
     * <p>This method preserves the interrupted flag of the current thread.
     *
     * @return true if pollExecutor is terminating, or the current thread is interrupted.
     */
    private boolean shouldTerminate() {
      return pollExecutor.isShutdown() || Thread.currentThread().isInterrupted();
    }
  }

  private class PollExecutionTask implements Poller.ThrowingRunnable {

    @Override
    public void run() throws Exception {
      T task = pollTask.poll();
      if (task != null) {
        taskExecutor.process(task);
      }
    }
  }

  private final class PollerUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

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
     * we log them in the most quite manner.
     *
     * @param t thread where the exception happened
     * @param e the exception itself
     */
    private void logPollExceptionsSuppressedDuringShutdown(Thread t, Throwable e) {
      log.trace(
          "Failure in thread {} is suppressed, considered normal during shutdown", t.getName(), e);
    }

    private boolean shouldIgnoreDuringShutdown(Throwable ex) {
      if (ex instanceof StatusRuntimeException) {
        if (GrpcUtils.isChannelShutdownException((StatusRuntimeException) ex)) {
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
  }
}
