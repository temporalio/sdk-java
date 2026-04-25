package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.failure.TemporalException;

/**
 * Thrown by {@link ActivityHandle#getResult()} when the standalone activity fails, times out, or is
 * cancelled. The original cause can be retrieved via {@link #getCause()}.
 */
@Experimental
public final class ActivityFailedException extends TemporalException {

  public ActivityFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
