package io.temporal.internal.retryer;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.internal.BackoffThrottler;
import io.temporal.serviceclient.RpcRetryOptions;
import java.util.concurrent.CancellationException;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GrpcSyncRetryer {
  private static final Logger log = LoggerFactory.getLogger(GrpcRetryer.class);

  public <R, T extends Throwable> R retry(
      GrpcRetryer.RetryableFunc<R, T> r,
      GrpcRetryer.GrpcRetryerOptions options,
      GetSystemInfoResponse.Capabilities serverCapabilities)
      throws T {
    options.validate();
    RpcRetryOptions rpcOptions = options.getOptions();
    @Nullable Deadline deadline = options.getDeadline();
    @Nullable
    Deadline retriesExpirationDeadline =
        GrpcRetryerUtils.mergeDurationWithAnAbsoluteDeadline(rpcOptions.getExpiration(), deadline);
    BackoffThrottler throttler =
        new BackoffThrottler(
            rpcOptions.getInitialInterval(),
            rpcOptions.getCongestionInitialInterval(),
            rpcOptions.getMaximumInterval(),
            rpcOptions.getBackoffCoefficient(),
            rpcOptions.getMaximumJitterCoefficient());

    int attempt = 0;
    StatusRuntimeException lastMeaningfulException = null;
    do {
      attempt++;

      try {
        long throttleMs = throttler.getSleepTime();
        if (throttleMs > 0) {
          Thread.sleep(throttleMs);
        }
        if (lastMeaningfulException != null) {
          log.debug("Retrying after failure", lastMeaningfulException);
        }
        R result = r.apply();
        throttler.success();
        return result;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationException();
      } catch (StatusRuntimeException e) {
        RuntimeException finalException =
            GrpcRetryerUtils.createFinalExceptionIfNotRetryable(e, rpcOptions, serverCapabilities);
        if (finalException != null) {
          log.debug("Final exception, throwing", finalException);
          throw finalException;
        }
        lastMeaningfulException =
            GrpcRetryerUtils.lastMeaningfulException(e, lastMeaningfulException);
        throttler.failure(e.getStatus().getCode());
      }
      // No catch block for any other exceptions because we don't retry them, we pass them through.
      // It's designed this way because it's GrpcRetryer, not general purpose retryer.
    } while (!GrpcRetryerUtils.ranOutOfRetries(
        rpcOptions, attempt, retriesExpirationDeadline, Context.current().getDeadline()));

    log.debug("Out of retries, throwing", lastMeaningfulException);
    rethrow(lastMeaningfulException);
    throw new IllegalStateException("unreachable");
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
}
