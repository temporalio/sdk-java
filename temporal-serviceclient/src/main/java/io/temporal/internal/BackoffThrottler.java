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

package io.temporal.internal;

import io.grpc.Context;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import javax.annotation.Nullable;

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
 *         throttler.throttle();
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
 * <p>or using async semantic
 *
 * <pre>
 * BackoffThrottler throttler = new BackoffThrottler(1000, 60000, 2);
 * while(!stopped) {
 *     try {
 *         Future&lt;Void&gt; t = throttler.throttleAsync();
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
public final class BackoffThrottler {

  private static final ScheduledExecutorService executor =
      new ScheduledThreadPoolExecutor(1, r -> new Thread(r, "async-backoff-throttler"));

  private final Duration initialSleep;

  private final Duration maxSleep;

  private final double backoffCoefficient;

  private int failureCount = 0;

  /**
   * Construct an instance of the throttler.
   *
   * @param initialSleep time to sleep on the first failure
   * @param maxSleep maximum time to sleep independently of number of failures
   * @param backoffCoefficient coefficient used to calculate the next time to sleep.
   */
  public BackoffThrottler(
      Duration initialSleep, @Nullable Duration maxSleep, double backoffCoefficient) {
    Objects.requireNonNull(initialSleep, "initialSleep");
    this.initialSleep = initialSleep;
    this.maxSleep = maxSleep;
    this.backoffCoefficient = backoffCoefficient;
  }

  private long calculateSleepTime() {
    double sleepMillis =
        Math.pow(backoffCoefficient, failureCount - 1) * initialSleep.toMillis();
    if (maxSleep != null) {
      return Math.min((long) sleepMillis, maxSleep.toMillis());
    }
    return (long) sleepMillis;
  }

  /**
   * Sleep if there were failures since the last success call.
   *
   * @throws InterruptedException
   */
  public void throttle() throws InterruptedException {
    if (failureCount > 0) {
      Thread.sleep(calculateSleepTime());
    }
  }

  /** Result future is done after a delay if there were failures since the last success call. */
  public CompletableFuture<Void> throttleAsync() {
    if (failureCount == 0) {
      return CompletableFuture.completedFuture(null);
    }
    CompletableFuture<Void> result = new CompletableFuture<>();
    long delay = calculateSleepTime();
    @SuppressWarnings({"FutureReturnValueIgnored", "unused"})
    ScheduledFuture<?> ignored =
        executor.schedule(
            // preserving gRPC context between threads
            Context.current().wrap(() -> result.complete(null)), delay, TimeUnit.MILLISECONDS);
    return result;
  }

  /** Reset failure count to 0. */
  public void success() {
    failureCount = 0;
  }

  /** Increment failure count. */
  public void failure() {
    failureCount++;
  }
}
