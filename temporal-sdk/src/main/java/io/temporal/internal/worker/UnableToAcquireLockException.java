package io.temporal.internal.worker;

/** Internal. Do not throw or catch in application level code. */
public final class UnableToAcquireLockException extends RuntimeException {

  public UnableToAcquireLockException(String message) {
    super(message);
  }
}
