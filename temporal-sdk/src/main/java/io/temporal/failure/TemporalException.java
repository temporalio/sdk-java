package io.temporal.failure;

/**
 * Base class for all exceptions thrown by Temporal SDK.
 *
 * <p>Do not extend by the application code.
 */
public class TemporalException extends RuntimeException {
  protected TemporalException(String message, Throwable cause) {
    super(message, cause, false, true);
  }
}
