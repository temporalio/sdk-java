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

import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShutdownManager implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(ShutdownManager.class);

  private final ScheduledExecutorService scheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor(
          new ExecutorThreadFactory(
              WorkerThreadsNameHelper.SHUTDOWN_MANAGER_THREAD_NAME_PREFIX, null));

  private static final int CHECK_PERIOD_MS = 250;

  /** executorToShutdown.shutdownNow() -&gt; timed wait for a graceful termination */
  public CompletableFuture<Void> shutdownExecutorNow(
      ExecutorService executorToShutdown, String executorName, Duration timeout) {
    executorToShutdown.shutdownNow();
    return limitedWait(executorToShutdown, executorName, timeout);
  }

  /** executorToShutdown.shutdownNow() -&gt; unlimited wait for termination */
  public CompletableFuture<Void> shutdownExecutorNowUntimed(
      ExecutorService executorToShutdown, String executorName) {
    executorToShutdown.shutdownNow();
    return untimedWait(executorToShutdown, executorName);
  }

  /**
   * executorToShutdown.shutdown() -&gt; timed wait for graceful termination ->
   * executorToShutdown.shutdownNow()
   */
  public CompletableFuture<Void> shutdownExecutor(
      ExecutorService executorToShutdown, String executorName, Duration timeout) {
    executorToShutdown.shutdown();

    return limitedWait(executorToShutdown, executorName, timeout);
  }

  /** executorToShutdown.shutdown() -&gt; unlimited wait for graceful termination */
  public CompletableFuture<Void> shutdownExecutorUntimed(
      ExecutorService executorToShutdown, String executorName) {
    executorToShutdown.shutdown();
    return untimedWait(executorToShutdown, executorName);
  }

  /**
   * Wait for {@code executorToShutdown} to terminate. Only completes the returned CompletableFuture
   * when the executor is terminated.
   */
  private CompletableFuture<Void> untimedWait(
      ExecutorService executorToShutdown, String executorName) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    scheduledExecutorService.submit(
        new ReportingDelayShutdown(executorToShutdown, executorName, future));
    return future;
  }

  /**
   * Wait for {@code executorToShutdown} to terminate for a defined interval, shutdownNow after
   * that. Always completes the returned CompletableFuture on termination of the executor or on a
   * timeout, whatever happens earlier.
   */
  private CompletableFuture<Void> limitedWait(
      ExecutorService executorToShutdown, String executorName, Duration timeout) {
    int attempts = (int) Math.ceil((double) timeout.toMillis() / CHECK_PERIOD_MS);

    CompletableFuture<Void> future = new CompletableFuture<>();
    scheduledExecutorService.submit(
        new LimitedWaitShutdown(executorToShutdown, attempts, executorName, future));
    return future;
  }

  @Override
  public void close() {
    scheduledExecutorService.shutdownNow();
  }

  private class LimitedWaitShutdown implements Runnable {
    private final ExecutorService executorToShutdown;
    private final CompletableFuture<Void> promise;
    private final int maxAttempts;
    private final String executorName;
    private int attempt;

    public LimitedWaitShutdown(
        ExecutorService executorToShutdown,
        int maxAttempts,
        String executorName,
        CompletableFuture<Void> promise) {
      this.executorToShutdown = executorToShutdown;
      this.promise = promise;
      this.maxAttempts = maxAttempts;
      this.executorName = executorName;
    }

    @Override
    public void run() {
      if (executorToShutdown.isTerminated()) {
        promise.complete(null);
        return;
      }
      attempt++;
      if (attempt > maxAttempts) {
        log.warn(
            "Wait for a graceful shutdown of {} timed out, fallback to shutdownNow()",
            executorName);
        executorToShutdown.shutdownNow();
        // we don't want to complicate shutdown with dealing of exceptions and errors of all sorts,
        // so just log and complete the promise
        promise.complete(null);
        return;
      }
      scheduledExecutorService.schedule(this, CHECK_PERIOD_MS, TimeUnit.MILLISECONDS);
    }
  }

  private class ReportingDelayShutdown implements Runnable {
    private static final int BLOCKED_REPORTING_THRESHOLD = 60;
    private static final int BLOCKED_REPORTING_PERIOD = 20;

    private final ExecutorService executorToShutdown;
    private final CompletableFuture<Void> promise;
    private final String executorName;
    private int attempt;

    public ReportingDelayShutdown(
        ExecutorService executorToShutdown, String executorName, CompletableFuture<Void> promise) {
      this.executorToShutdown = executorToShutdown;
      this.promise = promise;
      this.executorName = executorName;
    }

    @Override
    public void run() {
      if (executorToShutdown.isTerminated()) {
        if (attempt > BLOCKED_REPORTING_THRESHOLD) {
          // log warn only if we already logged a shutdown being delayed
          log.warn("{} successfully terminated", executorName);
        }
        promise.complete(null);
        return;
      }
      attempt++;
      // log a problem after BLOCKED_REPORTING_THRESHOLD attempts only
      if (attempt >= BLOCKED_REPORTING_THRESHOLD) {
        // and repeat every BLOCKED_REPORTING_PERIOD attempts
        if (((float) (attempt - BLOCKED_REPORTING_THRESHOLD) % BLOCKED_REPORTING_PERIOD) < 0.001) {
          log.warn(
              "Graceful shutdown of {} is blocked by one of the long currently processing tasks",
              executorName);
        }
      }
      scheduledExecutorService.schedule(this, CHECK_PERIOD_MS, TimeUnit.MILLISECONDS);
    }
  }
}
