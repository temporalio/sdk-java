package io.temporal.internal.statemachines;

/**
 * Thrown when {@link io.temporal.workflow.Workflow#continueAsNew} is called from a location that is
 * not supported.
 *
 * <p>The reason this class extends Error is for application workflow code to not catch it by
 * mistake. The default behavior of the SDK is to block workflow execution while Error is thrown.
 */
public class UnsupportedContinueAsNewRequest extends Error {
  public UnsupportedContinueAsNewRequest(String message) {
    super(message);
  }
}
