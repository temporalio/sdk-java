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

package io.temporal.serviceclient;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GrpcRetryer {

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

  private static final Logger log = LoggerFactory.getLogger(GrpcRetryer.class);

  public static <T extends Throwable> void retry(RpcRetryOptions options, RetryableProc<T> r)
      throws T {
    retryWithResult(
        options,
        () -> {
          r.apply();
          return null;
        });
  }

  public static <R, T extends Throwable> R retryWithResult(
      RpcRetryOptions options, RetryableFunc<R, T> r) throws T {
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
        throw new CancellationException();
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.CANCELLED) {
          throw new CancellationException();
        }
        throttler.failure();
        for (RpcRetryOptions.DoNotRetryPair pair : options.getDoNotRetry()) {
          if (pair.getCode() == e.getStatus().getCode()
              && (pair.getDetailsClass() == null
                  || StatusUtils.hasFailure(e, pair.getDetailsClass()))) {
            rethrow(e);
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
      RpcRetryOptions options, Supplier<CompletableFuture<R>> function) {
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
                unwrappedExceptionResult.completeExceptionally(CheckedExceptionWrapper.unwrap(e));
              }
              return null;
            });
    return unwrappedExceptionResult;
  }

  private static <R> CompletableFuture<R> retryWithResultAsync(
      RpcRetryOptions options,
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
              CompletableFuture<R> result;
              try {
                result = function.get();
              } catch (Throwable e) {
                throttler.failure();
                throw CheckedExceptionWrapper.wrap(e);
              }

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
      RpcRetryOptions options,
      Supplier<CompletableFuture<R>> function,
      int attempt,
      long startTime,
      AsyncBackoffThrottler throttler,
      R r,
      Throwable e) {
    if (e == null) {
      return new ValueExceptionPair<>(CompletableFuture.completedFuture(r), null);
    }
    // If exception is thrown from CompletionStage/CompletableFuture methods like compose or handle
    // - it gets wrapped into CompletionException, so here we need to unwrap it. We can get not
    // wrapped raw exception here too if CompletableFuture was explicitly filled with this exception
    // using CompletableFuture.completeExceptionally
    if (e instanceof CompletionException) {
      e = e.getCause();
    }
    // Do not retry if it's not StatusRuntimeException
    if (!(e instanceof StatusRuntimeException)) {
      return new ValueExceptionPair<>(null, e);
    }

    StatusRuntimeException exception = (StatusRuntimeException) e;
    long elapsed = System.currentTimeMillis() - startTime;
    for (RpcRetryOptions.DoNotRetryPair pair : options.getDoNotRetry()) {
      if (pair.getCode() == exception.getStatus().getCode()
          && (pair.getDetailsClass() == null
              || StatusUtils.hasFailure(exception, pair.getDetailsClass()))) {
        return new ValueExceptionPair<>(null, e);
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
  private GrpcRetryer() {}
}
