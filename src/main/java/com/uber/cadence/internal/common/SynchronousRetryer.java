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

import com.uber.cadence.common.RetryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SynchronousRetryer {

    public interface RetryableProc<E extends Throwable> {
        void apply() throws E;
    }

    public interface RetryableFunc<R, E extends Throwable> {
        R apply() throws E;
    }

    private static final Logger log = LoggerFactory.getLogger(SynchronousRetryer.class);


    public static <T extends Throwable> void retry(RetryOptions options, RetryableProc<T> r) throws T {
        retryWithResult(options, () -> {
            r.apply();
            return null;
        });
    }

    public static <R, T extends Throwable> R retryWithResult(RetryOptions options, RetryableFunc<R, T> r) throws T {
        int attempt = 0;
        long startTime = System.currentTimeMillis();
        BackoffThrottler throttler = new BackoffThrottler(options.getInitialInterval(),
                options.getMaximumInterval(), options.getBackoffCoefficient());
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
                if (options.getDoNotRetry() != null) {
                    for (Class<?> exceptionToNotRetry : options.getDoNotRetry()) {
                        if (exceptionToNotRetry.isAssignableFrom(e.getClass())) {
                            rethrow(e);
                        }
                    }
                }
                long elapsed = System.currentTimeMillis() - startTime;
                if (attempt >= options.getMaximumAttempts()
                        || (elapsed >= options.getExpiration().toMillis() && attempt >= options.getMinimumAttempts())) {
                    rethrow(e);
                }
                log.warn("Retrying after failure", e);
            }
        }
        while (true);
    }

    private static <T extends Throwable> void rethrow(Exception e) throws T {
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        } else {
            @SuppressWarnings("unchecked")
            T toRethrow = (T) e;
            throw toRethrow;
        }
    }

    /**
     * Prohibits instantiation.
     */
    private SynchronousRetryer() {

    }
}
