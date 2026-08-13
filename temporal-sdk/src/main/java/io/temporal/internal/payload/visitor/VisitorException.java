package io.temporal.internal.payload.visitor;

/**
 * Thrown when visiting the payloads or messages of a proto message fails. The original failure, if
 * any, is available via {@link #getCause()}.
 */
final class VisitorException extends RuntimeException {
  VisitorException(String message, Throwable cause) {
    super(message, cause);
  }

  VisitorException(String message) {
    super(message);
  }
}
