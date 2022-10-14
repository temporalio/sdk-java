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

import io.grpc.Status;
import io.grpc.Status.Code;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Used to throttle code execution in presence of failures using exponential backoff logic.
 *
 * <p>
 * The formula used to calculate the next sleep interval is:
 *
 * <p>
 *
 * <pre>
 * min(pow(backoffCoefficient, failureCount - 1) * initialSleep * (1 + jitter), maxSleep);
 * </pre>
 *
 * where <code>initialSleep</code> is either set to <code>regularInitialSleep</code> or <code>congestionInitialSleep</code>
 * based on the <i>most recent</i> failure. Note that it means that attempt X can possibly get a shorter throttle than
 * attempt X-1, if a non-congestion failure occurs after a congestion failure. This is the expected bahaviour for all SDK.
 *
 * <p>Example usage:
 *
 * <p>
 *
 * <pre>
 * BackoffThrottler throttler = new BackoffThrottler(50, 1000, 60000, 2, 0.1);
 * while(!stopped) {
 *     try {
 *         long throttleMs = throttler.getSleepTime();
 *         if (throttleMs > 0) {
 *             Thread.sleep(throttleMs);
 *         }
 *         // some code that can fail and should be throttled
 *         ...
 *         throttler.success();
 *     }
 *     catch (Exception e) {
 *         throttler.failure(
 *             (e instanceof StatusRuntimeException)
 *                 ? ((StatusRuntimeException) e).getStatus().getCode()
 *                 : Status.Code.UNKNOWN);
 *     }
 * }
 * </pre>
 *
 * @author fateev
 */
@NotThreadSafe
public final class BackoffThrottler {

  private final Duration regularInitialSleep;

  private final Duration congestionInitialSleep;

  private final Duration maxSleep;

  private final double backoffCoefficient;

  private final double maxJitter;

  private int failureCount = 0;

  private Status.Code lastFailureCode = Code.OK;

  /**
   * Construct an instance of the throttler.
   *
   * @param regularInitialSleep time to sleep on the first failure (assuming regular failures)
   * @param congestionInitialSleep time to sleep on the first failure (for congestion failures)
   * @param maxSleep maximum time to sleep independently of number of failures
   * @param backoffCoefficient coefficient used to calculate the next time to sleep
   * @param maxJitter maximum jitter to add or subtract
   */
  public BackoffThrottler(
      Duration regularInitialSleep,
      Duration congestionInitialSleep,
      @Nullable Duration maxSleep,
      double backoffCoefficient,
      double maxJitter) {
    Objects.requireNonNull(regularInitialSleep, "regularInitialSleep");
    Objects.requireNonNull(congestionInitialSleep, "congestionInitialSleep");
    if (backoffCoefficient < 1.0) {
      throw new IllegalArgumentException(
          "backoff coefficient less than 1.0: " + backoffCoefficient);
    }
    if (maxJitter < 0 || maxJitter >= 1.0) {
      throw new IllegalArgumentException("maximumJitter has to be >= 0 and < 1.0: " + maxJitter);
    }
    this.regularInitialSleep = regularInitialSleep;
    this.congestionInitialSleep = congestionInitialSleep;
    this.maxSleep = maxSleep;
    this.backoffCoefficient = backoffCoefficient;
    this.maxJitter = maxJitter;
  }

  public long getSleepTime() {
    if (failureCount == 0) return 0;
    Duration initial =
        (lastFailureCode == Code.RESOURCE_EXHAUSTED) ? congestionInitialSleep : regularInitialSleep;
    double jitter = Math.random() * maxJitter * 2 - maxJitter;
    double sleepMillis =
        Math.pow(backoffCoefficient, failureCount - 1) * initial.toMillis() * (1 + jitter);
    if (maxSleep != null) {
      return Math.min((long) sleepMillis, maxSleep.toMillis());
    }
    return (long) sleepMillis;
  }

  public int getAttemptCount() {
    return failureCount;
  }

  /** Reset failure count to 0 and clear last failure code. */
  public void success() {
    failureCount = 0;
    lastFailureCode = Code.OK;
  }

  /** Increment failure count and set last failure code. */
  public void failure(Status.Code failureCode) {
    failureCount++;
    lastFailureCode = failureCode;
  }
}
