package io.temporal.failure;

/** <b>This exception is expected to be thrown only by the Temporal framework code.</b> */
public final class TerminatedFailure extends TemporalFailure {

  public TerminatedFailure(String message, Throwable cause) {
    super(message, message, cause);
  }
}
