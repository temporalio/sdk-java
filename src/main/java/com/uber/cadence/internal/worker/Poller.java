/*
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

package com.uber.cadence.internal.worker;

import com.uber.cadence.internal.common.BackoffThrottler;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.m3.tally.Scope;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Poller<T> implements SuspendableWorker {

  public interface PollTask<TT> {
    TT poll() throws TException;
  }

  interface ThrowingRunnable {
    void run() throws Throwable;
  }

  private final String identity;
  private final Consumer<T> consumer;
  private final PollTask<T> pollTask;
  private final PollerOptions pollerOptions;
  private static final Logger log = LoggerFactory.getLogger(Poller.class);
  private ThreadPoolExecutor pollExecutor;
  private final Scope metricsScope;

  private final AtomicReference<CountDownLatch> suspendLatch = new AtomicReference<>();

  private BackoffThrottler pollBackoffThrottler;
  private Throttler pollRateThrottler;

  private Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
      (t, e) -> log.error("Failure in thread " + t.getName(), e);

  public Poller(
      String identity,
      PollTask<T> pollTask,
      Consumer<T> consumer,
      PollerOptions pollerOptions,
      Scope metricsScope) {
    Objects.requireNonNull(identity, "identity cannot be null");
    Objects.requireNonNull(pollTask, "poll service should not be null");
    Objects.requireNonNull(consumer, "consumer should not be null");
    Objects.requireNonNull(pollerOptions, "pollerOptions should not be null");
    Objects.requireNonNull(metricsScope, "metricsScope should not be null");

    this.identity = identity;
    this.pollTask = pollTask;
    this.consumer = consumer;
    this.pollerOptions = pollerOptions;
    this.metricsScope = metricsScope;
  }

  @Override
  public void start() {
    if (log.isInfoEnabled()) {
      log.info("start(): " + toString());
    }
    if (pollerOptions.getMaximumPollRatePerSecond() > 0.0) {
      pollRateThrottler =
          new Throttler(
              "poller",
              pollerOptions.getMaximumPollRatePerSecond(),
              pollerOptions.getMaximumPollRateIntervalMilliseconds());
    }

    // It is important to pass blocking queue of at least options.getPollThreadCount() capacity.
    // As task enqueues next task the buffering is needed to queue task until the previous one
    // releases a thread.
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

  private boolean isStarted() {
    return pollExecutor != null;
  }

  @Override
  public void shutdown() {
    log.info("shutdown");
    if (!isStarted()) {
      return;
    }
    pollExecutor.shutdown();
  }

  @Override
  public void shutdownNow() {
    log.info("shutdownNow poller=" + this.pollerOptions.getPollThreadNamePrefix());
    if (!isStarted()) {
      return;
    }
    pollExecutor.shutdownNow();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    if (pollExecutor == null) {
      // not started yet.
      return true;
    }
    return pollExecutor.awaitTermination(timeout, unit);
  }

  @Override
  public boolean shutdownAndAwaitTermination(long timeout, TimeUnit unit)
      throws InterruptedException {
    if (!isStarted()) {
      return true;
    }
    pollExecutor.shutdownNow();
    return pollExecutor.awaitTermination(timeout, unit);
  }

  @Override
  public boolean isRunning() {
    return isStarted() && !pollExecutor.isTerminated();
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
        if (pollExecutor.isTerminating()) {
          return;
        }
        pollBackoffThrottler.throttle();
        if (pollExecutor.isTerminating()) {
          return;
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

        if (pollExecutor.isTerminating()) {
          return;
        }
        task.run();
        pollBackoffThrottler.success();
      } catch (Throwable e) {
        pollBackoffThrottler.failure();
        if (!(e.getCause() instanceof InterruptedException)) {
          uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), e);
        }
      } finally {
        // Resubmit itself back to pollExecutor
        if (!pollExecutor.isTerminating()) {
          pollExecutor.execute(this);
        } else {
          log.info("poll loop done");
        }
      }
    }
  }

  private class PollExecutionTask implements Poller.ThrowingRunnable {
    private Semaphore pollSemaphore;

    PollExecutionTask() {
      this.pollSemaphore = new Semaphore(pollerOptions.getPollThreadCount());
    }

    @Override
    public void run() throws Exception {
      try {
        pollSemaphore.acquire();
        T task = pollTask.poll();
        if (task == null) {
          return;
        }
        consumer.accept(task);
      } finally {
        pollSemaphore.release();
      }
    }
  }
}
