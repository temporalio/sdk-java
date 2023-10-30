package io.temporal.internal.sync;

/**
 * This exception is thrown when a workflow tries to perform an operation that could generate a new
 * command while it is in a read only context.
 */
public class ReadOnlyException extends IllegalStateException {
  public ReadOnlyException(String action) {
    super("While in read-only function, action attempted: " + action);
  }
}
