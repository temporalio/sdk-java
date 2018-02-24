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
package com.uber.cadence.workflow;

import java.time.Duration;
import java.util.Objects;

public final class RetryOptions {

    public final static class Builder {

        private Duration initialInterval;

        private Duration expiration;

        private double backoffCoefficient = 2;

        private int maximumAttempts = Integer.MAX_VALUE;

        private long minimumAttempts;

        private Duration maximumInterval;

        private Functions.Func1<Exception, Boolean> exceptionFilter = (e) -> true;


        /**
         * Interval of the first retry. If coefficient is 1.0 then it is used for all retries.
         * Required!
         */
        public Builder setInitialInterval(Duration initialInterval) {
            Objects.requireNonNull(initialInterval);
            if (initialInterval.isNegative() || initialInterval.isZero()) {
                throw new IllegalArgumentException("Invalid interval: " + initialInterval);
            }
            this.initialInterval = initialInterval;
            return this;
        }

        /**
         * Maximum time to retry. Null means forever.
         * When exceeded the retries stop even if maximum retries is not reached yet.
         */
        public Builder setExpiration(Duration expiration) {
            if (expiration != null && (expiration.isNegative() || expiration.isZero())) {
                throw new IllegalArgumentException("Invalid interval: " + expiration);
            }
            this.expiration = expiration;
            return this;
        }

        /**
         * Coefficient used to calculate the next retry interval.
         * The next retry interval is previous interval multiplied by this coefficient.
         * Must be 1 or larger.
         */
        public Builder setBackoffCoefficient(double backoffCoefficient) {
            if (backoffCoefficient < 1.0) {
                throw new IllegalArgumentException("coefficient less than 1");
            }
            this.backoffCoefficient = backoffCoefficient;
            return this;
        }

        /**
         * Maximum number of attempts. When exceeded the retries stop even if not expired yet.
         * Must be 1 or bigger.
         */
        public Builder setMaximumAttempts(int maximumAttempts) {
            if (maximumAttempts < 1) {
                throw new IllegalArgumentException("less than 1");
            }
            this.maximumAttempts = maximumAttempts;
            return this;
        }

        /**
         * Minimum number of retries. Even if expired will retry until this number is reached.
         * Must be 1 or bigger.
         */
        public Builder setMinimumAttempts(long minimumAttempts) {
            this.minimumAttempts = minimumAttempts;
            return this;
        }

        /**
         * Maximum interval between retries. Exponential backoff leads to interval increase.
         * This value is the cap of the increase.
         */
        public Builder setMaximumInterval(Duration maximumInterval) {
            Objects.requireNonNull(maximumInterval);
            if (maximumInterval != null && (maximumInterval.isNegative() || maximumInterval.isZero())) {
                throw new IllegalArgumentException("Invalid interval: " + maximumInterval);
            }
            this.maximumInterval = maximumInterval;
            return this;
        }

        /**
         * Returns true if exception should retried.
         * {@link Error} and {@link java.util.concurrent.CancellationException} are never retried and
         * are not even passed to this filter. null means retry everything else.
         */
        public Builder setExceptionFilter(Functions.Func1<Exception, Boolean> exceptionFilter) {
            this.exceptionFilter = exceptionFilter;
            return this;
        }

        public RetryOptions build() {
            if (initialInterval == null) {
                throw new IllegalStateException("required property initialInterval not set");
            }
            if (maximumInterval != null && maximumInterval.compareTo(initialInterval) == -1) {
                throw new IllegalStateException("maximumInterval(" + maximumInterval
                        + ") cannot be smaller than initialInterval(" + initialInterval);
            }
            return new RetryOptions(initialInterval, backoffCoefficient, expiration, maximumAttempts, minimumAttempts, maximumInterval,
                    exceptionFilter == null ? (e)->true : exceptionFilter);
        }
    }

    private final Duration initialInterval;

    private final double backoffCoefficient;

    private final Duration expiration;

    private final int maximumAttempts;

    private final long minimumAttempts;

    private final Duration maximumInterval;

    private final Functions.Func1<Exception, Boolean> exceptionFilter;

    private RetryOptions(Duration initialInterval, double backoffCoefficient, Duration expiration, int maximumAttempts,
                         long minimumAttempts, Duration maximumInterval, Functions.Func1<Exception, Boolean> exceptionFilter) {
        this.initialInterval = initialInterval;
        this.backoffCoefficient = backoffCoefficient;
        this.expiration = expiration;
        this.maximumAttempts = maximumAttempts;
        this.minimumAttempts = minimumAttempts;
        this.maximumInterval = maximumInterval;
        this.exceptionFilter = exceptionFilter;
    }

    public Duration getInitialInterval() {
        return initialInterval;
    }

    public double getBackoffCoefficient() {
        return backoffCoefficient;
    }

    public Duration getExpiration() {
        return expiration;
    }

    public int getMaximumAttempts() {
        return maximumAttempts;
    }

    public long getMinimumAttempts() {
        return minimumAttempts;
    }

    public Duration getMaximumInterval() {
        return maximumInterval;
    }

    public Functions.Func1<Exception, Boolean> getExceptionFilter() {
        return exceptionFilter;
    }

    @Override
    public String toString() {
        return "RetryOptions{" +
                "initialInterval=" + initialInterval +
                ", backoffCoefficient=" + backoffCoefficient +
                ", expiration=" + expiration +
                ", maximumAttempts=" + maximumAttempts +
                ", minimumAttempts=" + minimumAttempts +
                ", maximumInterval=" + maximumInterval +
                ", exceptionFilter=" + exceptionFilter +
                '}';
    }
}
