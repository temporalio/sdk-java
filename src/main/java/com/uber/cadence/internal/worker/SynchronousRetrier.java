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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class SynchronousRetrier<T extends Throwable> {

    public interface RetryableProc<E extends Throwable> {
        void apply() throws E;
    }

    public interface RetryableFunc<R, E extends Throwable> {
        R apply() throws E;
    }

    private static final Log log = LogFactory.getLog(SynchronousRetrier.class);

    private final ExponentialRetryParameters retryParameters;

    private final Class<?>[] exceptionsToNotRetry;

    public SynchronousRetrier(ExponentialRetryParameters retryParameters, Class<?>... exceptionsToNotRetry) {
        if (retryParameters.getBackoffCoefficient() < 0) {
            throw new IllegalArgumentException("negative backoffCoefficient");
        }
        if (retryParameters.getInitialInterval() < 10) {
            throw new IllegalArgumentException("initialInterval cannot be less then 10: " + retryParameters.getInitialInterval());
        }
        if (retryParameters.getExpirationInterval() < retryParameters.getInitialInterval()) {
            throw new IllegalArgumentException("expirationInterval < initialInterval");
        }
        if (retryParameters.getMaximumRetries() < retryParameters.getMinimumRetries()) {
            throw new IllegalArgumentException("maximumRetries < minimumRetries");
        }
        this.retryParameters = retryParameters;
        this.exceptionsToNotRetry = exceptionsToNotRetry;
    }

    public ExponentialRetryParameters getRetryParameters() {
        return retryParameters;
    }

    public Class<?>[] getExceptionsToNotRetry() {
        return exceptionsToNotRetry;
    }

    public void retry(RetryableProc<T> r) throws T {
        retryWithResult(() -> {
            r.apply();
            return null;
        });
    }

    public <R> R retryWithResult(RetryableFunc<R, T> r) throws T {
        int attempt = 0;
        long startTime = System.currentTimeMillis();
        BackoffThrottler throttler = new BackoffThrottler(retryParameters.getInitialInterval(),
                retryParameters.getMaximumRetryInterval(), retryParameters.getBackoffCoefficient());
        do {
            try {
                attempt++;
                throttler.throttle();
                R result = r.apply();
                throttler.success();
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                throttler.failure();
                for (Class<?> exceptionToNotRetry : exceptionsToNotRetry) {
                    if (exceptionToNotRetry.isAssignableFrom(e.getClass())) {
                        rethrow(e);
                    }
                }
                long elapsed = System.currentTimeMillis() - startTime;
                if (attempt > retryParameters.getMaximumRetries()
                        || (elapsed >= retryParameters.getExpirationInterval() && attempt > retryParameters.getMinimumRetries())) {
                    rethrow(e);
                }
                log.warn("Retrying after failure", e);
            }
        }
        while (true);
    }

    private void rethrow(Exception e) throws T {
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        } else {
            @SuppressWarnings("unchecked")
            T toRethrow = (T) e;
            throw toRethrow;
        }
    }
}
