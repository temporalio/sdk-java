package io.temporal.workflow;

/**
 * Actions taken if a workflow terminates with running handlers.
 *
 * <p>Policy defining actions taken when a workflow exits while update or signal handlers are
 * running. The workflow exit may be due to successful return, failure, cancellation, or
 * continue-as-new.
 */
public enum HandlerUnfinishedPolicy {
  /** Issue a warning in addition to abandon. */
  WARN_AND_ABANDON,
  /**
   * Abandon the handler.
   *
   * <p>In the case of an update handler this means that the client will receive an error rather
   * than the update result.
   */
  ABANDON,
}
