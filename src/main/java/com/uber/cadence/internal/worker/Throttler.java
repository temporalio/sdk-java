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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Throttler {

  private static final Logger log = LoggerFactory.getLogger(Throttler.class);

  /** Human readable name of the resource being throttled. Used for logging only. */
  private final String name;

  /** Used as a circular buffer */
  private CircularLongBuffer checkPointTimes;

  /** Used as an index to a circular buffer */
  private long index;

  /** Interval used to measure the rate. Shorter interval allows less spikey rates. */
  private long rateInterval;

  private final long rateIntervalMilliseconds;

  private long overslept;

  /**
   * Construct throttler.
   *
   * @param name Human readable name of the resource being throttled. Used for logging only.
   * @param maxRatePerSecond maximum rate allowed
   * @param rateIntervalMilliseconds rate measurement interval. Interval should be at least 1000 /
   *     maxRatePerSecond.
   */
  public Throttler(String name, double maxRatePerSecond, long rateIntervalMilliseconds) {
    if (null == name) {
      throw new IllegalArgumentException("null name");
    }
    this.name = name;
    if (maxRatePerSecond <= 0) {
      throw new IllegalArgumentException("0 or negative maxRatePerSecond");
    }
    if (rateIntervalMilliseconds <= 0) {
      throw new IllegalArgumentException("0 or negative rateIntervalMilliseconds");
    }
    synchronized (this) {
      this.rateIntervalMilliseconds = rateIntervalMilliseconds;
      setMaxRatePerSecond(maxRatePerSecond);
    }
  }

  public synchronized void setMaxRatePerSecond(double maxRatePerSecond) {
    int maxMessagesPerRateInterval = (int) (maxRatePerSecond * rateIntervalMilliseconds / 1000);
    if (maxMessagesPerRateInterval == 0) {
      maxMessagesPerRateInterval = 1;
      rateInterval = (long) (1.0 / maxRatePerSecond * 1000.0);
    } else {
      rateInterval = rateIntervalMilliseconds;
    }
    if (checkPointTimes != null) {
      int oldSize = checkPointTimes.size();
      checkPointTimes =
          checkPointTimes.copy(index - maxMessagesPerRateInterval, maxMessagesPerRateInterval);
      index = Math.min(checkPointTimes.size(), oldSize);
    } else {
      checkPointTimes = new CircularLongBuffer(maxMessagesPerRateInterval);
      index = 0;
    }
    log.debug("new rate=" + maxRatePerSecond + " (msg/sec)");
  }

  public synchronized void throttle(int count) throws InterruptedException {
    for (int i = 0; i < count; ++i) {
      throttle();
    }
  }

  /**
   * When called on each request sleeps if called faster then configured average rate.
   *
   * @throws InterruptedException when destroyRequested
   */
  public synchronized void throttle() throws InterruptedException {
    long now = System.currentTimeMillis();
    long checkPoint = checkPointTimes.get(index);
    if (checkPoint > 0) {
      long elapsed = now - checkPoint;

      // if the time for this window is less than the minimum per window
      if (elapsed >= 0 && elapsed < rateInterval) {
        long sleepInterval = rateInterval - elapsed - overslept;
        overslept = 0;
        if (sleepInterval > 0) {
          if (log.isTraceEnabled()) {
            log.debug(
                "Throttling "
                    + name
                    + ": called "
                    + checkPointTimes.size()
                    + " times in last "
                    + elapsed
                    + " milliseconds. Going to sleep for "
                    + sleepInterval
                    + " milliseconds.");
          }
          long t = System.currentTimeMillis();
          Thread.sleep(sleepInterval);
          overslept = System.currentTimeMillis() - t - sleepInterval;
        }
      }
    }
    checkPointTimes.set(index++, System.currentTimeMillis());
  }
}
