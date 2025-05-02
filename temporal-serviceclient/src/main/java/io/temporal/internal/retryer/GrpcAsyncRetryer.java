package io.temporal.internal.retryer;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.internal.BackoffThrottler;
import io.temporal.serviceclient.RpcRetryOptions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GrpcAsyncRetryer<R> {
  private static final Logger log = LoggerFactory.getLogger(GrpcRetryer.class);

  private final ScheduledExecutorService executor;
  private final GrpcRetryer.GrpcRetryerOptions options;
  private final GetSystemInfoResponse.Capabilities serverCapabilities;
  private final Supplier<CompletableFuture<R>> function;
  private final BackoffThrottler throttler;
  private final Deadline retriesExpirationDeadline;
  private StatusRuntimeException lastMeaningfulException = null;

  public GrpcAsyncRetryer(
      ScheduledExecutorService asyncThrottlerExecutor,
      Supplier<CompletableFuture<R>> function,
      GrpcRetryer.GrpcRetryerOptions options,
      GetSystemInfoResponse.Capabilities serverCapabilities) {

    options.validate();

    this.executor = asyncThrottlerExecutor;
    this.options = options;
    this.serverCapabilities = serverCapabilities;
    this.function = function;

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
