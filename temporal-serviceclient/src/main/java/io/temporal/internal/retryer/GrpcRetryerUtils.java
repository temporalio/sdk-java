package io.temporal.internal.retryer;

import static io.grpc.Status.Code.DEADLINE_EXCEEDED;

import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.StatusUtils;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class GrpcRetryerUtils {
  /**
   * This method encapsulates the logic if {@code StatusRuntimeException exception} is retryable or
   * not.
   *
   * @param currentException exception to analyze
   * @param options retry options
   * @param serverCapabilities server capabilities defining the retry behavior
   * @return null if the {@code exception} can be retried, a final exception to throw in the
   *     external code otherwise.
   */
  static @Nullable RuntimeException createFinalExceptionIfNotRetryable(
      @Nonnull StatusRuntimeException currentException,
      @Nonnull RpcRetryOptions options,
      GetSystemInfoResponse.Capabilities serverCapabilities) {
    Status status = currentException.getStatus();

    switch (status.getCode()) {
      // CANCELLED and DEADLINE_EXCEEDED usually considered non-retryable in GRPC world, for
      // example:
      // https://github.com/grpc-ecosystem/go-grpc-middleware/blob/master/retry/retry.go#L287
      case CANCELLED:
        return new CancellationException("The gRPC request was cancelled");
      case INVALID_ARGUMENT:
      case NOT_FOUND:
      case ALREADY_EXISTS:
      case FAILED_PRECONDITION:
      case PERMISSION_DENIED:
      case UNAUTHENTICATED:
      case UNIMPLEMENTED:
        // never retry these codes
        return currentException;
      case INTERNAL:
        // false and unset is the same for this flag, no need for has* check
        if (serverCapabilities.getInternalErrorDifferentiation()) {
          return currentException;
        }
        break;
      case DEADLINE_EXCEEDED:
        // By default, we keep retrying with DEADLINE_EXCEEDED assuming that it's the deadline of
        // one attempt which expired, but not the whole sequence.
        break;
      case RESOURCE_EXHAUSTED:
        // Retry RESOURCE_EXHAUSTED unless the max message size was exceeded
        GrpcMessageTooLargeException e = GrpcMessageTooLargeException.tryWrap(currentException);
        if (e != null) {
          return e;
        }
        break;
      default:
        for (RpcRetryOptions.DoNotRetryItem pair : options.getDoNotRetry()) {
          if (pair.getCode() == status.getCode()
              && (pair.getDetailsClass() == null
                  || StatusUtils.hasFailure(currentException, pair.getDetailsClass()))) {
            return currentException;
          }
        }
    }

    return null;
  }

  static StatusRuntimeException lastMeaningfulException(
      @Nonnull StatusRuntimeException currentException,
      @Nullable StatusRuntimeException previousException) {
    if (previousException != null
        && currentException.getStatus().getCode() == DEADLINE_EXCEEDED
        && previousException.getStatus().getCode() != DEADLINE_EXCEEDED) {
      // If there was another exception before this DEADLINE_EXCEEDED that wasn't
      // DEADLINE_EXCEEDED, we prefer it over DEADLINE_EXCEEDED
      return previousException;
    }

    return currentException;
  }

  /**
   * @param options retry options
   * @param retriesExpirationDeadline deadline calculated based on {@link
   *     RpcRetryOptions#getExpiration()}
   * @param attempt number of the last made attempt
   * @param grpcContextDeadline deadline coming from gRPC context of the request
   * @return true if we out of attempts or time to retry
   */
  static boolean ranOutOfRetries(
      RpcRetryOptions options,
      int attempt,
      @Nullable Deadline retriesExpirationDeadline,
      @Nullable Deadline grpcContextDeadline) {
    int maxAttempts = options.getMaximumAttempts();

    return (maxAttempts > 0 && attempt >= maxAttempts)
        // check if the deadline for the whole sequence calculated based on {@link
        // RpcRetryOptions#getExpiration()} is expired
        || (retriesExpirationDeadline != null && retriesExpirationDeadline.isExpired())
        // if context has a gRPC deadline, no requests will be possible anyway, the best we can do
        // is wrap up
        || (grpcContextDeadline != null && grpcContextDeadline.isExpired());
  }

  @Nullable
  static Deadline mergeDurationWithAnAbsoluteDeadline(
      @Nullable Duration duration, @Nullable Deadline deadline) {
    Deadline durationDeadline = null;
    if (duration != null) {
      long ms = duration.toMillis();
      if (ms > 0) {
        durationDeadline = Deadline.after(ms, TimeUnit.MILLISECONDS);
      } else {
        long ns = duration.toNanos();
        durationDeadline = Deadline.after(ns, TimeUnit.NANOSECONDS);
      }
    }

    if (deadline != null) {
      return (durationDeadline != null) ? deadline.minimum(durationDeadline) : deadline;
    } else {
      return durationDeadline;
    }
  }
}
