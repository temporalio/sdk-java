package io.temporal.failure;

import io.temporal.proto.failure.Failure;

public final class TerminatedException extends RemoteException {
  public TerminatedException(Failure failure, Exception cause) {
    super(failure, cause);
    if (!failure.hasTerminatedFailureInfo()) {
      throw new IllegalArgumentException(
          "Terminated failure expected: " + failure.getFailureInfoCase());
    }
  }
}
