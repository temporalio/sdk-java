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

package io.temporal.internal.retryer;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.StatusRuntimeException;
import io.temporal.internal.AsyncBackoffThrottler;
import io.temporal.serviceclient.RpcRetryOptions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GrpcAsyncRetryer {
  private static final Logger log = LoggerFactory.getLogger(GrpcAsyncRetryer.class);

  public <R> CompletableFuture<R> retry(
      Supplier<CompletableFuture<R>> function, GrpcRetryer.GrpcRetryerOptions options) {
    options.validate();
    RpcRetryOptions rpcOptions = options.getOptions();
    @Nullable Deadline deadline = options.getDeadline();
    @Nullable
    Deadline retriesExpirationDeadline =
        GrpcRetryerUtils.mergeDurationWithAnAbsoluteDeadline(rpcOptions.getExpiration(), deadline);
    AsyncBackoffThrottler throttler =
        new AsyncBackoffThrottler(
            rpcOptions.getInitialInterval(),
            rpcOptions.getMaximumInterval(),
            rpcOptions.getBackoffCoefficient());

    int attempt = 1;
    CompletableFuture<R> resultCF = new CompletableFuture<>();
    retry(rpcOptions, function, attempt, retriesExpirationDeadline, throttler, null, resultCF);
    return resultCF;
  }

  private <R> void retry(
      RpcRetryOptions options,
      Supplier<CompletableFuture<R>> function,
      int attempt,
      @Nullable Deadline retriesExpirationDeadline,
      AsyncBackoffThrottler throttler,
      StatusRuntimeException previousException,
      CompletableFuture<R> resultCF) {
    throttler
        .throttle()
        .thenAccept(
            (ignore) -> {
              // try-catch is because get() call might throw.
              CompletableFuture<R> result;

              try {
                result = function.get();
              } catch (Throwable e) {
                throttler.failure();
                // function isn't supposed to throw exceptions, it should always return a
                // CompletableFuture even if it's a failed one.
                // But if this happens - process the same way as it would be an exception from
                // completable future
                // Do not retry if it's not StatusRuntimeException
                failOrRetry(
                    options,
                    function,
                    attempt,
                    retriesExpirationDeadline,
                    throttler,
                    previousException,
                    e,
                    resultCF);
                return;
              }
              if (result == null) {
                resultCF.complete(null);
                return;
              }

              result.whenComplete(
                  (r, e) -> {
                    if (e == null) {
                      throttler.success();
                      resultCF.complete(r);
                    } else {
                      throttler.failure();
                      failOrRetry(
                          options,
                          function,
                          attempt,
                          retriesExpirationDeadline,
                          throttler,
                          previousException,
                          e,
                          resultCF);
                    }
                  });
            });
  }

  private <R> void failOrRetry(
      RpcRetryOptions options,
      Supplier<CompletableFuture<R>> function,
      int attempt,
      @Nullable Deadline retriesExpirationDeadline,
      AsyncBackoffThrottler throttler,
      StatusRuntimeException previousException,
      Throwable currentException,
      CompletableFuture<R> resultCF) {

    // If exception is thrown from CompletionStage/CompletableFuture methods like compose or handle
    // - it gets wrapped into CompletionException, so here we need to unwrap it. We can get not
    // wrapped raw exception here too if CompletableFuture was explicitly filled with this exception
    // using CompletableFuture.completeExceptionally
    currentException = unwrapCompletionException(currentException);

    // Do not retry if it's not StatusRuntimeException
    if (!(currentException instanceof StatusRuntimeException)) {
      resultCF.completeExceptionally(currentException);
      return;
    }

    StatusRuntimeException statusRuntimeException = (StatusRuntimeException) currentException;

    RuntimeException finalException =
        GrpcRetryerUtils.createFinalExceptionIfNotRetryable(statusRuntimeException, options);
    if (finalException != null) {
      log.warn("Non retryable failure", finalException);
      resultCF.completeExceptionally(finalException);
      return;
    }

    StatusRuntimeException lastMeaningfulException =
        GrpcRetryerUtils.lastMeaningfulException(statusRuntimeException, previousException);
    if (GrpcRetryerUtils.ranOutOfRetries(
        options, attempt, retriesExpirationDeadline, Context.current().getDeadline())) {
      log.warn("Failure, out of retries", lastMeaningfulException);
      resultCF.completeExceptionally(lastMeaningfulException);
    } else {
      log.info("Retrying after failure", currentException);
      retry(
          options,
          function,
          attempt + 1,
          retriesExpirationDeadline,
          throttler,
          lastMeaningfulException,
          resultCF);
    }
  }

  private static Throwable unwrapCompletionException(Throwable e) {
    return e instanceof CompletionException ? e.getCause() : e;
  }
}
