package io.temporal.internal.retryer;

public class GrpcMessageTooLargeException extends RuntimeException {
  public GrpcMessageTooLargeException(io.grpc.StatusRuntimeException cause) {
    super(cause.getMessage(), cause);
  }
}
