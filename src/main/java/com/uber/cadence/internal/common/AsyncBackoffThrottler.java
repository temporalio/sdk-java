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

package com.uber.cadence.internal.common;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Used to throttle code execution in presence of failures using exponential backoff logic. The
 * formula used to calculate the next sleep interval is:
 *
 * <p>
 *
 * <pre>
 * min(pow(backoffCoefficient, failureCount - 1) * initialSleep, maxSleep);
 * </pre>
 *
 * <p>Example usage:
 *
 * <p>
 *
 * <pre>
 * BackoffThrottler throttler = new BackoffThrottler(1000, 60000, 2);
 * while(!stopped) {
 *     try {
 *         Future&lt;Void&gt; t = throttler.throttle();
 *         t.get();
 *         // some code that can fail and should be throttled
 *         ...
 *         throttler.success();
 *     }
 *     catch (Exception e) {
 *         throttler.failure();
 *     }
 * }
 * </pre>
 *
 * @author fateev
 */
public final class AsyncBackoffThrottler {

  private static final ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);

  private final Duration initialSleep;

  private final Duration maxSleep;

  private final double backoffCoefficient;

  private final AtomicLong failureCount = new AtomicLong();

  /**
   * Construct an instance of the throttler.
   *
   * @param initialSleep time to sleep on the first failure
   * @param maxSleep maximum time to sleep independently of number of failures
   * @param backoffCoefficient coefficient used to calculate the next time to sleep.
   */
  public AsyncBackoffThrottler(
      Duration initialSleep, Duration maxSleep, double backoffCoefficient) {
    Objects.requireNonNull(initialSleep, "initialSleep");
    if (backoffCoefficient < 1.0) {
      throw new IllegalArgumentException(
          "backoff coefficient less than 1.0: " + backoffCoefficient);
    }
    this.initialSleep = initialSleep;
    this.maxSleep = maxSleep;
    this.backoffCoefficient = backoffCoefficient;
  }

  private long calculateSleepTime() {
    double sleepMillis =
        Math.pow(backoffCoefficient, failureCount.get() - 1) * initialSleep.toMillis();
    if (maxSleep != null) {
      return Math.min((long) sleepMillis, maxSleep.toMillis());
    }
    return (long) sleepMillis;
  }

  /** Result future is done after a delay if there were failures since the last success call. */
  public CompletableFuture<Void> throttle() {
    if (failureCount.get() == 0) {
      return CompletableFuture.completedFuture(null);
    }
    CompletableFuture<Void> result = new CompletableFuture<>();
    long delay = calculateSleepTime();
    @SuppressWarnings({"FutureReturnValueIgnored", "unused"})
    ScheduledFuture<?> ignored =
        executor.schedule(() -> result.complete(null), delay, TimeUnit.MILLISECONDS);
    return result;
  }

  /** Resent failure count to 0. */
  public void success() {
    failureCount.set(0);
  }

  /** Increment failure count. */
  public void failure() {
    failureCount.incrementAndGet();
  }
}
