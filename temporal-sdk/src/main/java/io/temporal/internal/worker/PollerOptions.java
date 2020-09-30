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

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Options for component that polls Temporal task queues for tasks. */
public final class PollerOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(PollerOptions options) {
    return new Builder(options);
  }

  public static PollerOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final PollerOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = PollerOptions.newBuilder().build();
  }

  public static final class Builder {

    private int maximumPollRateIntervalMilliseconds = 1000;

    private double maximumPollRatePerSecond;

    private double pollBackoffCoefficient = 2;

    private Duration pollBackoffInitialInterval = Duration.ofMillis(100);

    private Duration pollBackoffMaximumInterval = Duration.ofMinutes(1);

    private int pollThreadCount = 1;

    private String pollThreadNamePrefix;

    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    private Builder() {}

    private Builder(PollerOptions options) {
      if (options == null) {
        return;
      }
      this.maximumPollRateIntervalMilliseconds = options.getMaximumPollRateIntervalMilliseconds();
      this.maximumPollRatePerSecond = options.getMaximumPollRatePerSecond();
      this.pollBackoffCoefficient = options.getPollBackoffCoefficient();
      this.pollBackoffInitialInterval = options.getPollBackoffInitialInterval();
      this.pollBackoffMaximumInterval = options.getPollBackoffMaximumInterval();
      this.pollThreadCount = options.getPollThreadCount();
      this.pollThreadNamePrefix = options.getPollThreadNamePrefix();
      this.uncaughtExceptionHandler = options.getUncaughtExceptionHandler();
    }

    /** Defines interval for measuring poll rate. Larger the interval more spiky can be the load. */
    public Builder setMaximumPollRateIntervalMilliseconds(int maximumPollRateIntervalMilliseconds) {
      this.maximumPollRateIntervalMilliseconds = maximumPollRateIntervalMilliseconds;
      return this;
    }

    /**
     * Maximum rate of polling. Measured in the interval set through {@link
     * #setMaximumPollRateIntervalMilliseconds(int)}.
     */
    public Builder setMaximumPollRatePerSecond(double maximumPollRatePerSecond) {
      this.maximumPollRatePerSecond = maximumPollRatePerSecond;
      return this;
    }

    /** Coefficient to use when calculating exponential delay in case of failures */
    public Builder setPollBackoffCoefficient(double pollBackoffCoefficient) {
      this.pollBackoffCoefficient = pollBackoffCoefficient;
      return this;
    }

    /**
     * Initial delay in case of failure. If backoff coefficient is 1 then it would be the constant
     * delay between failing polls.
     */
    public Builder setPollBackoffInitialInterval(Duration pollBackoffInitialInterval) {
      this.pollBackoffInitialInterval = pollBackoffInitialInterval;
      return this;
    }

    /** Maximum interval between polls in case of failures. */
    public Builder setPollBackoffMaximumInterval(Duration pollBackoffMaximumInterval) {
      this.pollBackoffMaximumInterval = pollBackoffMaximumInterval;
      return this;
    }

    /** Number of parallel polling threads. */
    public Builder setPollThreadCount(int pollThreadCount) {
      this.pollThreadCount = pollThreadCount;
      return this;
    }

    /** Called to report unexpected exceptions in the poller threads. */
    public Builder setUncaughtExceptionHandler(
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
      this.uncaughtExceptionHandler = uncaughtExceptionHandler;
      return this;
    }

    /** Prefix to use when naming poller threads. */
    public Builder setPollThreadNamePrefix(String pollThreadNamePrefix) {
      this.pollThreadNamePrefix = pollThreadNamePrefix;
      return this;
    }

    public PollerOptions build() {
      if (uncaughtExceptionHandler == null) {
        uncaughtExceptionHandler = (t, e) -> log.error("uncaught exception", e);
      }
      return new PollerOptions(
          maximumPollRateIntervalMilliseconds,
          maximumPollRatePerSecond,
          pollBackoffCoefficient,
          pollBackoffInitialInterval,
          pollBackoffMaximumInterval,
          pollThreadCount,
          uncaughtExceptionHandler,
          pollThreadNamePrefix);
    }
  }

  private static final Logger log = LoggerFactory.getLogger(PollerOptions.class);

  private final int maximumPollRateIntervalMilliseconds;

  private final double maximumPollRatePerSecond;

  private final double pollBackoffCoefficient;

  private final Duration pollBackoffInitialInterval;

  private final Duration pollBackoffMaximumInterval;

  private final int pollThreadCount;

  private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

  private final String pollThreadNamePrefix;

  private PollerOptions(
      int maximumPollRateIntervalMilliseconds,
      double maximumPollRatePerSecond,
      double pollBackoffCoefficient,
      Duration pollBackoffInitialInterval,
      Duration pollBackoffMaximumInterval,
      int pollThreadCount,
      Thread.UncaughtExceptionHandler uncaughtExceptionHandler,
      String pollThreadNamePrefix) {
    this.maximumPollRateIntervalMilliseconds = maximumPollRateIntervalMilliseconds;
    this.maximumPollRatePerSecond = maximumPollRatePerSecond;
    this.pollBackoffCoefficient = pollBackoffCoefficient;
    this.pollBackoffInitialInterval = pollBackoffInitialInterval;
    this.pollBackoffMaximumInterval = pollBackoffMaximumInterval;
    this.pollThreadCount = pollThreadCount;
    this.uncaughtExceptionHandler = uncaughtExceptionHandler;
    this.pollThreadNamePrefix = pollThreadNamePrefix;
  }

  public int getMaximumPollRateIntervalMilliseconds() {
    return maximumPollRateIntervalMilliseconds;
  }

  public double getMaximumPollRatePerSecond() {
    return maximumPollRatePerSecond;
  }

  public double getPollBackoffCoefficient() {
    return pollBackoffCoefficient;
  }

  public Duration getPollBackoffInitialInterval() {
    return pollBackoffInitialInterval;
  }

  public Duration getPollBackoffMaximumInterval() {
    return pollBackoffMaximumInterval;
  }

  public int getPollThreadCount() {
    return pollThreadCount;
  }

  public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
    return uncaughtExceptionHandler;
  }

  public String getPollThreadNamePrefix() {
    return pollThreadNamePrefix;
  }

  @Override
  public String toString() {
    return "PollerOptions{"
        + "maximumPollRateIntervalMilliseconds="
        + maximumPollRateIntervalMilliseconds
        + ", maximumPollRatePerSecond="
        + maximumPollRatePerSecond
        + ", pollBackoffCoefficient="
        + pollBackoffCoefficient
        + ", pollBackoffInitialInterval="
        + pollBackoffInitialInterval
        + ", pollBackoffMaximumInterval="
        + pollBackoffMaximumInterval
        + ", pollThreadCount="
        + pollThreadCount
        + ", pollThreadNamePrefix='"
        + pollThreadNamePrefix
        + '\''
        + '}';
  }
}
