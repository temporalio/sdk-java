package io.temporal.failure;

import javax.annotation.Nullable;

/**
 * Exceptions originated at the Temporal service.
 *
 * <p><b>This exception is expected to be thrown only by the Temporal framework code.</b>
 */
public final class ServerFailure extends TemporalFailure {
  private final boolean nonRetryable;

  public ServerFailure(String message, boolean nonRetryable) {
    this(message, nonRetryable, null);
  }

  public ServerFailure(String message, boolean nonRetryable, @Nullable Throwable cause) {
    super(message, message, cause);
    this.nonRetryable = nonRetryable;
  }

  public boolean isNonRetryable() {
    return nonRetryable;
  }
}
