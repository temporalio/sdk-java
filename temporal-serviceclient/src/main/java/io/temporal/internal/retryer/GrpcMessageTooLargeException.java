package io.temporal.internal.retryer;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import javax.annotation.Nullable;

/**
 * Internal exception used to mark when StatusRuntimeException is caused by message being too large.
 * Exceptions are only wrapped if {@link GrpcRetryer} was used, which is an implementation detail
 * and not always the case - user code should catch {@link StatusRuntimeException}.
 */
public class GrpcMessageTooLargeException extends StatusRuntimeException {
  private GrpcMessageTooLargeException(Status status, @Nullable Metadata trailers) {
    super(status, trailers);
  }

  public static @Nullable GrpcMessageTooLargeException tryWrap(StatusRuntimeException exception) {
    Status status = exception.getStatus();
    if (status.getCode() == Status.Code.RESOURCE_EXHAUSTED
        && status.getDescription() != null
        && (status.getDescription().startsWith("grpc: received message larger than max")
            || status
                .getDescription()
                .startsWith("grpc: message after decompression larger than max")
            || status
                .getDescription()
                .startsWith("grpc: received message after decompression larger than max"))) {
      return new GrpcMessageTooLargeException(status.withCause(exception), exception.getTrailers());
    } else {
      return null;
    }
  }
}
