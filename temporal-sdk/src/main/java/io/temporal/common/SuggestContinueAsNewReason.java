package io.temporal.common;

/**
 * Reason(s) why the server suggests a workflow should continue-as-new. Multiple reasons can be true
 * at the same time.
 */
@Experimental
public enum SuggestContinueAsNewReason {
  /** Workflow history size is getting too large. */
  HISTORY_SIZE_TOO_LARGE,
  /** Workflow history has too many events. */
  TOO_MANY_HISTORY_EVENTS,
  /** Workflow's count of completed plus in-flight updates is too large. */
  TOO_MANY_UPDATES
}
