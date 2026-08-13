package io.temporal.internal.payload.visitor;

/**
 * Exception thrown only by test visitor/message callbacks. Using a dedicated type keeps "the
 * visitor threw" assertions from being satisfied by an unrelated {@link IllegalStateException} that
 * production code might raise.
 */
class TestVisitorException extends RuntimeException {
  TestVisitorException(String message) {
    super(message);
  }
}
