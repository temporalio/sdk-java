package io.temporal.failure;

/**
 * The cause of a {@link ChildWorkflowFailure} when the child workflow could not start because its
 * versioning override was invalid.
 *
 * <p><b>This exception is expected to be thrown only by the Temporal framework code.</b>
 */
public final class InvalidVersioningOverrideFailure extends TemporalFailure {

  public InvalidVersioningOverrideFailure() {
    super("Invalid versioning override", "Invalid versioning override", null);
  }
}
