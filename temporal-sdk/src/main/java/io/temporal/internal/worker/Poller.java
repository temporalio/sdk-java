/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.serviceclient.BackoffThrottler;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Poller<T> implements SuspendableWorker {

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
  private final Scope metricsScope;

  private final AtomicReference<CountDownLatch> suspendLatch = new AtomicReference<>();

  private BackoffThrottler pollBackoffThrottler;
  private Throttler pollRateThrottler;

  private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
      new PollerUncaughtExceptionHandler();

  public Poller(
      String identity,
      PollTask<T> pollTask,
      ShutdownableTaskExecutor<T> taskExecutor,
      PollerOptions pollerOptions,
      Scope metricsScope) {
    Objects.requireNonNull(identity, "identity cannot be null");
    Objects.requireNonNull(pollTask, "poll service should not be null");
    Objects.requireNonNull(taskExecutor, "taskExecutor should not be null");
    Objects.requireNonNull(pollerOptions, "pollerOptions should not be null");
    Objects.requireNonNull(metricsScope, "metricsScope should not be null");

    this.identity = identity;
    this.pollTask = pollTask;
    this.taskExecutor = taskExecutor;
    this.pollerOptions = pollerOptions;
    this.metricsScope = metricsScope;
  }

  @Override
  public void start() {
    if (log.isInfoEnabled()) {
      log.info("start(): " + this);
    }
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

    pollBackoffThrottler =
        new BackoffThrottler(
            pollerOptions.getPollBackoffInitialInterval(),
            pollerOptions.getPollBackoffMaximumInterval(),
            pollerOptions.getPollBackoffCoefficient());
    for (int i = 0; i < pollerOptions.getPollThreadCount(); i++) {
      pollExecutor.execute(new PollLoopTask(new PollExecutionTask()));
      metricsScope.counter(MetricsType.POLLER_START_COUNTER).inc(1);
    }
  }

  @Override
  public boolean isStarted() {
    return pollExecutor != null;
  }

  @Override
  public boolean isShutdown() {
    return pollExecutor.isShutdown() && taskExecutor.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return pollExecutor.isTerminated() && taskExecutor.isTerminated();
  }

  @Override
  public void shutdown() {
    log.info("shutdown");
    if (!isStarted()) {
      return;
    }
    // shutdownNow and then await to stop long polling and ensure that no new tasks are dispatched
    // to the taskExecutor.
    pollExecutor.shutdownNow();
    try {
      pollExecutor.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    taskExecutor.shutdown();
  }

  @Override
  public void shutdownNow() {
    log.info("shutdownNow poller={}", this.pollerOptions.getPollThreadNamePrefix());
    if (!isStarted()) {
      return;
    }
    pollExecutor.shutdownNow();
    taskExecutor.shutdownNow();
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    if (!isStarted()) {
      return;
    }
    long timeoutMillis = unit.toMillis(timeout);
    timeoutMillis = InternalUtils.awaitTermination(pollExecutor, timeoutMillis);
    InternalUtils.awaitTermination(taskExecutor, timeoutMillis);
  }

  @Override
  public void suspendPolling() {
    log.info("suspendPolling");
    suspendLatch.set(new CountDownLatch(1));
  }

  @Override
  public void resumePolling() {
    log.info("resumePolling");
    CountDownLatch existing = suspendLatch.getAndSet(null);
    if (existing != null) {
      existing.countDown();
    }
  }

  @Override
  public boolean isSuspended() {
    return suspendLatch.get() != null;
  }

  @Override
  public String toString() {
    return "Poller{" + "options=" + pollerOptions + ", identity=" + identity + '}';
  }

  private class PollLoopTask implements Runnable {

    private final Poller.ThrowingRunnable task;

    PollLoopTask(Poller.ThrowingRunnable task) {
      this.task = task;
    }

    @Override
    public void run() {
      try {
        pollBackoffThrottler.throttle();
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
        }
        pollBackoffThrottler.failure();
        uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), e);
      } finally {
        if (!shouldTerminate()) {
          // Resubmit itself back to pollExecutor
          pollExecutor.execute(this);
        } else {
          log.info("poll loop is terminated");
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
      if (task == null) {
        return;
      }
      taskExecutor.process(task);
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
          log.warn("Failure in thread {}", t.getName(), e);
          return;
        }
      }
      log.error("Failure in thread {}", t.getName(), e);
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
