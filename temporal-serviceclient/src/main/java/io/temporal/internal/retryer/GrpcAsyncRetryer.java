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

import io.grpc.*;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse.Capabilities;
import io.temporal.internal.BackoffThrottler;
import io.temporal.internal.retryer.GrpcRetryer.GrpcRetryerOptions;
import io.temporal.serviceclient.RpcRetryOptions;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GrpcAsyncRetryer<R> {
  private static final Logger log = LoggerFactory.getLogger(GrpcRetryer.class);

  private final ScheduledExecutorService executor;
  private final Supplier<GetSystemInfoResponse.Capabilities> serverCapabilities;
  private final Supplier<CompletableFuture<R>> function;
  private final GrpcRetryer.GrpcRetryerOptions options;
  private final BackoffThrottler throttler;
  private final Deadline retriesExpirationDeadline;
  private StatusRuntimeException lastMeaningfulException = null;

  public static <R> CompletableFuture<R> retry(
      ScheduledExecutorService asyncThrottlerExecutor,
      Supplier<Capabilities> serverCapabilities,
      Supplier<CompletableFuture<R>> function,
      GrpcRetryerOptions options) {
    return new GrpcAsyncRetryer<>(asyncThrottlerExecutor, serverCapabilities, function, options)
        .retry();
  }

  private GrpcAsyncRetryer(
      ScheduledExecutorService asyncThrottlerExecutor,
      Supplier<Capabilities> serverCapabilities,
      Supplier<CompletableFuture<R>> function,
      GrpcRetryerOptions options) {

    options.validate();

    this.executor = asyncThrottlerExecutor;
    this.serverCapabilities = serverCapabilities;
    this.function = function;
    this.options = options;

    RpcRetryOptions rpcOptions = options.getOptions();
    this.retriesExpirationDeadline =
        GrpcRetryerUtils.mergeDurationWithAnAbsoluteDeadline(
            rpcOptions.getExpiration(), options.getDeadline());
    this.throttler =
        new BackoffThrottler(
            rpcOptions.getInitialInterval(),
            rpcOptions.getCongestionInitialInterval(),
            rpcOptions.getMaximumInterval(),
            rpcOptions.getBackoffCoefficient(),
            rpcOptions.getMaximumJitterCoefficient());
  }

  public CompletableFuture<R> retry() {
    CompletableFuture<R> resultCF = new CompletableFuture<>();
    retry(resultCF);
    return resultCF;
  }

  private void retry(CompletableFuture<R> resultCF) {
    CompletableFuture<Void> throttleFuture = new CompletableFuture<>();
    @SuppressWarnings({"FutureReturnValueIgnored", "unused"})
    ScheduledFuture<?> ignored =
        executor.schedule(
            // preserving gRPC context between threads
            Context.current().wrap(() -> throttleFuture.complete(null)),
            throttler.getSleepTime(),
            TimeUnit.MILLISECONDS);

    throttleFuture.thenAccept(
        (ignore) -> {
          if (lastMeaningfulException != null) {
            log.debug("Retrying after failure", lastMeaningfulException);
          }

          // try-catch is because get() call might throw.
          try {
            CompletableFuture<R> result = function.get();
            if (result == null) result = CompletableFuture.completedFuture(null);

            result.whenComplete(
                (r, e) -> {
                  if (e == null) {
                    throttler.success();
                    resultCF.complete(r);
                  } else {
                    throttler.failure(
                        (e instanceof StatusRuntimeException)
                            ? ((StatusRuntimeException) e).getStatus().getCode()
                            : Status.Code.UNKNOWN);
                    failOrRetry(e, resultCF);
                  }
                });

          } catch (Throwable e) {
            throttler.failure(
                (e instanceof StatusRuntimeException)
                    ? ((StatusRuntimeException) e).getStatus().getCode()
                    : Status.Code.UNKNOWN);
            // function isn't supposed to throw exceptions, it should always return a
            // CompletableFuture even if it's a failed one.
            // But if this happens - process the same way as it would be an exception from
            // completable future
            // Do not retry if it's not StatusRuntimeException
            failOrRetry(e, resultCF);
          }
        });
  }

  private void failOrRetry(Throwable currentException, CompletableFuture<R> resultCF) {

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

    this.lastMeaningfulException =
        GrpcRetryerUtils.lastMeaningfulException(statusRuntimeException, lastMeaningfulException);
    if (GrpcRetryerUtils.ranOutOfRetries(
        options.getOptions(),
        this.throttler.getAttemptCount(),
        this.retriesExpirationDeadline,
        Context.current().getDeadline())) {
      log.debug("Out of retries, throwing", lastMeaningfulException);
      resultCF.completeExceptionally(lastMeaningfulException);
    } else {
      retry(resultCF);
    }
  }

  private static Throwable unwrapCompletionException(Throwable e) {
    return e instanceof CompletionException ? e.getCause() : e;
  }
}
