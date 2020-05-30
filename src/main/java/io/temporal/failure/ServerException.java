package io.temporal.failure;

import io.temporal.proto.failure.Failure;

public final class ServerException extends RemoteException {
  private final boolean nonRetryable;

  public ServerException(Failure failure, Exception cause) {
    super(failure, cause);
    if (!failure.hasServerFailureInfo()) {
      throw new IllegalArgumentException(
          "Server failure expected: " + failure.getFailureInfoCase());
    }
    this.nonRetryable = failure.getServerFailureInfo().getNonRetryable();
  }

  public boolean isNonRetryable() {
    return nonRetryable;
  }
}
