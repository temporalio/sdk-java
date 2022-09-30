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

package io.temporal.internal.retryer;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.internal.BackoffThrottler;
import io.temporal.serviceclient.RpcRetryOptions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GrpcAsyncRetryer {
  private static final Logger log = LoggerFactory.getLogger(GrpcRetryer.class);

  public <R> CompletableFuture<R> retry(
      Supplier<CompletableFuture<R>> function,
      GrpcRetryer.GrpcRetryerOptions options,
      GetSystemInfoResponse.Capabilities serverCapabilities) {
    options.validate();
    RpcRetryOptions rpcOptions = options.getOptions();
    @Nullable Deadline deadline = options.getDeadline();
    @Nullable
    Deadline retriesExpirationDeadline =
        GrpcRetryerUtils.mergeDurationWithAnAbsoluteDeadline(rpcOptions.getExpiration(), deadline);
    BackoffThrottler throttler =
        new BackoffThrottler(
            rpcOptions.getInitialInterval(),
            rpcOptions.getMaximumInterval(),
            rpcOptions.getBackoffCoefficient());

    int attempt = 1;
    CompletableFuture<R> resultCF = new CompletableFuture<>();
    retry(
        options,
        serverCapabilities,
        function,
        attempt,
        retriesExpirationDeadline,
        throttler,
        null,
        resultCF);
    return resultCF;
  }

  private <R> void retry(
      GrpcRetryer.GrpcRetryerOptions options,
      GetSystemInfoResponse.Capabilities serverCapabilities,
      Supplier<CompletableFuture<R>> function,
      int attempt,
      @Nullable Deadline retriesExpirationDeadline,
      BackoffThrottler throttler,
      StatusRuntimeException previousException,
      CompletableFuture<R> resultCF) {
    throttler
        .throttleAsync()
        .thenAccept(
            (ignore) -> {
              if (previousException != null) {
                log.debug("Retrying after failure", previousException);
              }

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
                    serverCapabilities,
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
                          serverCapabilities,
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
      GrpcRetryer.GrpcRetryerOptions options,
      GetSystemInfoResponse.Capabilities serverCapabilities,
      Supplier<CompletableFuture<R>> function,
      int attempt,
      @Nullable Deadline retriesExpirationDeadline,
      BackoffThrottler throttler,
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
        GrpcRetryerUtils.createFinalExceptionIfNotRetryable(
            statusRuntimeException, options.getOptions(), serverCapabilities);
    if (finalException != null) {
      log.debug("Final exception, throwing", finalException);
      resultCF.completeExceptionally(finalException);
      return;
    }

    StatusRuntimeException lastMeaningfulException =
        GrpcRetryerUtils.lastMeaningfulException(statusRuntimeException, previousException);
    if (GrpcRetryerUtils.ranOutOfRetries(
        options.getOptions(),
        attempt,
        retriesExpirationDeadline,
        Context.current().getDeadline())) {
      log.debug("Out of retries, throwing", lastMeaningfulException);
      resultCF.completeExceptionally(lastMeaningfulException);
    } else {
      retry(
          options,
          serverCapabilities,
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
