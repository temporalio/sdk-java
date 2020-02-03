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

package io.temporal.internal.common;

import static io.temporal.internal.common.CheckedExceptionWrapper.unwrap;

import io.temporal.*;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Retryer {
  public static final RetryOptions DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS;

  private static final Duration RETRY_SERVICE_OPERATION_INITIAL_INTERVAL = Duration.ofMillis(20);
  private static final Duration RETRY_SERVICE_OPERATION_EXPIRATION_INTERVAL = Duration.ofMinutes(1);
  private static final double RETRY_SERVICE_OPERATION_BACKOFF = 1.2;

  static {
    RetryOptions.Builder roBuilder =
        new RetryOptions.Builder()
            .setInitialInterval(RETRY_SERVICE_OPERATION_INITIAL_INTERVAL)
            .setExpiration(RETRY_SERVICE_OPERATION_EXPIRATION_INTERVAL)
            .setBackoffCoefficient(RETRY_SERVICE_OPERATION_BACKOFF);

    Duration maxInterval = RETRY_SERVICE_OPERATION_EXPIRATION_INTERVAL.dividedBy(10);
    if (maxInterval.compareTo(RETRY_SERVICE_OPERATION_INITIAL_INTERVAL) < 0) {
      maxInterval = RETRY_SERVICE_OPERATION_INITIAL_INTERVAL;
    }
    roBuilder.setMaximumInterval(maxInterval);
    roBuilder.setDoNotRetry(
        BadRequestError.class,
        EntityNotExistsError.class,
        WorkflowExecutionAlreadyStartedError.class,
        DomainAlreadyExistsError.class,
        QueryFailedError.class,
        DomainNotActiveError.class,
        CancellationAlreadyRequestedError.class);
    DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS = roBuilder.validateBuildWithDefaults();
  }

  public interface RetryableProc<E extends Throwable> {

    void apply() throws E;
  }

  public interface RetryableFunc<R, E extends Throwable> {

    R apply() throws E;
  }

  /**
   * Used to pass failure to a {@link java.util.concurrent.CompletionStage#thenCompose(Function)}
   * which doesn't include exception parameter like {@link
   * java.util.concurrent.CompletionStage#handle(BiFunction)} does.
   */
  private static class ValueExceptionPair<V> {

    private final CompletableFuture<V> value;
    private final Throwable exception;

    public ValueExceptionPair(CompletableFuture<V> value, Throwable exception) {
      this.value = value;
      this.exception = exception;
    }

    public CompletableFuture<V> getValue() {
      return value;
    }

    public Throwable getException() {
      return exception;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(Retryer.class);

  public static <T extends Throwable> void retry(RetryOptions options, RetryableProc<T> r)
      throws T {
    retryWithResult(
        options,
        () -> {
          r.apply();
          return null;
        });
  }

  public static <R, T extends Throwable> R retryWithResult(
      RetryOptions options, RetryableFunc<R, T> r) throws T {
    int attempt = 0;
    long startTime = System.currentTimeMillis();
    BackoffThrottler throttler =
        new BackoffThrottler(
            options.getInitialInterval(),
            options.getMaximumInterval(),
            options.getBackoffCoefficient());
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
        int maxAttempts = options.getMaximumAttempts();
        Duration expiration = options.getExpiration();
        if ((maxAttempts > 0 && attempt >= maxAttempts)
            || (expiration != null && elapsed >= expiration.toMillis())) {
          rethrow(e);
        }
        log.warn("Retrying after failure", e);
      }
    } while (true);
  }

  public static <R> CompletableFuture<R> retryWithResultAsync(
      RetryOptions options, Supplier<CompletableFuture<R>> function) {
    int attempt = 0;
    long startTime = System.currentTimeMillis();
    AsyncBackoffThrottler throttler =
        new AsyncBackoffThrottler(
            options.getInitialInterval(),
            options.getMaximumInterval(),
            options.getBackoffCoefficient());
    // Need this to unwrap checked exception.
    CompletableFuture<R> unwrappedExceptionResult = new CompletableFuture<>();
    CompletableFuture<R> result =
        retryWithResultAsync(options, function, attempt + 1, startTime, throttler);
    @SuppressWarnings({"FutureReturnValueIgnored", "unused"})
    CompletableFuture<Void> ignored =
        result.handle(
            (r, e) -> {
              if (e == null) {
                unwrappedExceptionResult.complete(r);
              } else {
                unwrappedExceptionResult.completeExceptionally(unwrap(e));
              }
              return null;
            });
    return unwrappedExceptionResult;
  }

  private static <R> CompletableFuture<R> retryWithResultAsync(
      RetryOptions options,
      Supplier<CompletableFuture<R>> function,
      int attempt,
      long startTime,
      AsyncBackoffThrottler throttler) {
    options.validate();
    return throttler
        .throttle()
        .thenCompose(
            (ignore) -> {
              // try-catch is because get() call might throw.
              try {
                CompletableFuture<R> result = function.get();
                if (result == null) {
                  return CompletableFuture.completedFuture(null);
                }
                return result.handle(
                    (r, e) -> {
                      if (e == null) {
                        throttler.success();
                        return r;
                      } else {
                        throttler.failure();
                        throw CheckedExceptionWrapper.wrap(e);
                      }
                    });
              } catch (Throwable e) {
                throttler.failure();
                throw CheckedExceptionWrapper.wrap(e);
              }
            })
        .handle((r, e) -> failOrRetry(options, function, attempt, startTime, throttler, r, e))
        .thenCompose(
            (pair) -> {
              if (pair.getException() != null) {
                throw CheckedExceptionWrapper.wrap(pair.getException());
              }
              return pair.getValue();
            });
  }

  /** Using {@link ValueExceptionPair} as future#thenCompose doesn't include exception parameter. */
  private static <R> ValueExceptionPair<R> failOrRetry(
      RetryOptions options,
      Supplier<CompletableFuture<R>> function,
      int attempt,
      long startTime,
      AsyncBackoffThrottler throttler,
      R r,
      Throwable e) {
    if (e == null) {
      return new ValueExceptionPair<>(CompletableFuture.completedFuture(r), null);
    }
    if (e instanceof CompletionException) {
      e = e.getCause();
    }
    // Do not retry Error
    if (e instanceof Error) {
      return new ValueExceptionPair<>(null, e);
    }
    e = unwrap((Exception) e);
    long elapsed = System.currentTimeMillis() - startTime;
    if (options.getDoNotRetry() != null) {
      for (Class<?> exceptionToNotRetry : options.getDoNotRetry()) {
        if (exceptionToNotRetry.isAssignableFrom(e.getClass())) {
          return new ValueExceptionPair<>(null, e);
        }
      }
    }
    int maxAttempts = options.getMaximumAttempts();
    if ((maxAttempts > 0 && attempt >= maxAttempts)
        || (options.getExpiration() != null && elapsed >= options.getExpiration().toMillis())) {
      return new ValueExceptionPair<>(null, e);
    }
    log.debug("Retrying after failure", e);
    CompletableFuture<R> next =
        retryWithResultAsync(options, function, attempt + 1, startTime, throttler);
    return new ValueExceptionPair<>(next, null);
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

  /** Prohibits instantiation. */
  private Retryer() {}
}
