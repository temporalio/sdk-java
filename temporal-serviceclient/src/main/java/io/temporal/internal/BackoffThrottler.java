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

import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

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
 * <pre><code>
 * BackoffThrottler throttler = new BackoffThrottler(1000, 60000, 2);
 * while(!stopped) {
 *     try {
 *         long throttleMs = throttler.getSleepTime();
 *         if (throttleMs &gt; 0) {
 *             Thread.sleep(throttleMs);
 *         }
 *         // some code that can fail and should be throttled
 *         ...
 *         throttler.success();
 *     }
 *     catch (Exception e) {
 *         throttler.failure();
 *     }
 * }
 * </code></pre>
 *
 * @author fateev
 */
@NotThreadSafe
public final class BackoffThrottler {

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

  public long getSleepTime() {
    if (failureCount == 0) return 0;
    double sleepMillis = Math.pow(backoffCoefficient, failureCount - 1) * initialSleep.toMillis();
    if (maxSleep != null) {
      return Math.min((long) sleepMillis, maxSleep.toMillis());
    }
    return (long) sleepMillis;
  }

  public int getAttemptCount() {
    return failureCount;
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
