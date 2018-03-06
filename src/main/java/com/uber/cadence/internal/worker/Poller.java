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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Calls a passed task in a loop according to {@link PollerOptions}.
 */
final class Poller implements SuspendableWorker {

    interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private class PollServiceTask implements Runnable {

        private final ThrowingRunnable task;

        PollServiceTask(ThrowingRunnable task) {
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

    private static final Logger log = LoggerFactory.getLogger(Poller.class);

    private final PollerOptions options;

    private final ThrowingRunnable task;

    private ThreadPoolExecutor pollExecutor;

    private final AtomicReference<CountDownLatch> suspendLatch = new AtomicReference<>();

    private BackoffThrottler pollBackoffThrottler;

    private Throttler pollRateThrottler;

    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler = (t, e) -> log.error("Failure in thread " + t.getName(), e);

    Poller(PollerOptions options, ThrowingRunnable task) {
        this.options = options;
        this.task = task;
    }

    @Override
    public void start() {
        if (log.isInfoEnabled()) {
            log.info("start(): " + toString());
        }
        if (options.getMaximumPollRatePerSecond() > 0.0) {
            pollRateThrottler = new Throttler("poller", options.getMaximumPollRatePerSecond(),
                    options.getMaximumPollRateIntervalMilliseconds());
        }

        pollExecutor = new ThreadPoolExecutor(options.getPollThreadCount(), options.getPollThreadCount(),
                1, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(options.getPollThreadCount()));
        pollExecutor.setThreadFactory(new ExecutorThreadFactory(options.getPollThreadNamePrefix(),
                options.getUncaughtExceptionHandler()));

        pollBackoffThrottler = new BackoffThrottler(options.getPollBackoffInitialInterval(),
                options.getPollBackoffMaximumInterval(),
                options.getPollBackoffCoefficient());
        for (int i = 0; i < options.getPollThreadCount(); i++) {
            pollExecutor.execute(new PollServiceTask(task));
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
        log.info("shutdownNow poller=" + this.options.getPollThreadNamePrefix());
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
        boolean result = pollExecutor.awaitTermination(timeout, unit);
        log.info("awaitTermination done");
        return result;
    }

    @Override
    public boolean shutdownAndAwaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        log.info("shutdownAndAwaitTermination poller=" + this.options.getPollThreadNamePrefix());
        if (!isStarted()) {
            return true;
        }
        pollExecutor.shutdownNow();
        boolean result = pollExecutor.awaitTermination(timeout, unit);
        log.info("shutdownAndAwaitTermination done");
        return result;
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
        return "Poller{" +
                "options=" + options +
                '}';
    }
}
