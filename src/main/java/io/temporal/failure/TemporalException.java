package io.temporal.failure;

public class TemporalException extends RuntimeException {
  protected TemporalException(String message, Throwable cause) {
    super(message, cause, true, true);
  }
}
