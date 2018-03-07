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

import java.time.Duration;

/**
 * TODO: Switch to Duration.
 */
public final class PollerOptions {

    public final static class Builder {

        private int maximumPollRateIntervalMilliseconds = 1000;

        private double maximumPollRatePerSecond;

        private double pollBackoffCoefficient = 2;

        private Duration pollBackoffInitialInterval = Duration.ofMillis(100);

        private Duration pollBackoffMaximumInterval = Duration.ofMinutes(1);

        private int pollThreadCount = 1;

        private String pollThreadNamePrefix;

        private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

        public Builder() {
        }

        public Builder(PollerOptions o) {
            if (o == null) {
                return;
            }
            this.maximumPollRateIntervalMilliseconds = o.getMaximumPollRateIntervalMilliseconds();
            this.maximumPollRatePerSecond = o.getMaximumPollRatePerSecond();
            this.pollBackoffCoefficient = o.getPollBackoffCoefficient();
            this.pollBackoffInitialInterval = o.getPollBackoffInitialInterval();
            this.pollBackoffMaximumInterval = o.getPollBackoffMaximumInterval();
            this.pollThreadCount = o.getPollThreadCount();
            this.pollThreadNamePrefix = o.getPollThreadNamePrefix();
            this.uncaughtExceptionHandler = o.getUncaughtExceptionHandler();
        }

        public Builder setMaximumPollRateIntervalMilliseconds(int maximumPollRateIntervalMilliseconds) {
            this.maximumPollRateIntervalMilliseconds = maximumPollRateIntervalMilliseconds;
            return this;
        }

        public Builder setMaximumPollRatePerSecond(double maximumPollRatePerSecond) {
            this.maximumPollRatePerSecond = maximumPollRatePerSecond;
            return this;
        }

        public Builder setPollBackoffCoefficient(double pollBackoffCoefficient) {
            this.pollBackoffCoefficient = pollBackoffCoefficient;
            return this;
        }

        public Builder setPollBackoffInitialInterval(Duration pollBackoffInitialInterval) {
            this.pollBackoffInitialInterval = pollBackoffInitialInterval;
            return this;
        }

        public Builder setPollBackoffMaximumInterval(Duration pollBackoffMaximumInterval) {
            this.pollBackoffMaximumInterval = pollBackoffMaximumInterval;
            return this;
        }

        public Builder setPollThreadCount(int pollThreadCount) {
            this.pollThreadCount = pollThreadCount;
            return this;
        }

        public Builder setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
            this.uncaughtExceptionHandler = uncaughtExceptionHandler;
            return this;
        }

        public Builder setPollThreadNamePrefix(String pollThreadNamePrefix) {
            this.pollThreadNamePrefix = pollThreadNamePrefix;
            return this;
        }

        public PollerOptions build() {
            if (uncaughtExceptionHandler == null) {
                uncaughtExceptionHandler = (t, e) -> e.printStackTrace();
            }
            return new PollerOptions(maximumPollRateIntervalMilliseconds, maximumPollRatePerSecond,
                    pollBackoffCoefficient, pollBackoffInitialInterval, pollBackoffMaximumInterval,
                    pollThreadCount, uncaughtExceptionHandler, pollThreadNamePrefix);
        }
    }

    private final int maximumPollRateIntervalMilliseconds;

    private final double maximumPollRatePerSecond;

    private final double pollBackoffCoefficient;

    private final Duration pollBackoffInitialInterval;

    private final Duration pollBackoffMaximumInterval;

    private final int pollThreadCount;

    private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    private final String pollThreadNamePrefix;

    private PollerOptions(int maximumPollRateIntervalMilliseconds, double maximumPollRatePerSecond,
                          double pollBackoffCoefficient, Duration pollBackoffInitialInterval,
                          Duration pollBackoffMaximumInterval, int pollThreadCount,
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
        return "PollerOptions{" +
                "maximumPollRateIntervalMilliseconds=" + maximumPollRateIntervalMilliseconds +
                ", maximumPollRatePerSecond=" + maximumPollRatePerSecond +
                ", pollBackoffCoefficient=" + pollBackoffCoefficient +
                ", pollBackoffInitialInterval=" + pollBackoffInitialInterval +
                ", pollBackoffMaximumInterval=" + pollBackoffMaximumInterval +
                ", pollThreadCount=" + pollThreadCount +
                ", pollThreadNamePrefix='" + pollThreadNamePrefix + '\'' +
                '}';
    }
}