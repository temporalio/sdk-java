package io.temporal.internal.replay;

final class StaleWorkflowHistoryException extends RuntimeException {

  private static final String CACHE_PROGRESS_MISMATCH_MESSAGE =
      "Server history for the workflow is below the progress of the workflow on the worker, the progress needs to be discarded";

  private StaleWorkflowHistoryException(String message) {
    super(message);
  }

  static IllegalStateException newCacheProgressMismatch() {
    return new IllegalStateException(
        CACHE_PROGRESS_MISMATCH_MESSAGE,
        new StaleWorkflowHistoryException(CACHE_PROGRESS_MISMATCH_MESSAGE));
  }

  static IllegalStateException newPrematureEndOfStream(
      long expectedLastEventId, long processedEventId) {
    String message =
        String.format(
            "Premature end of stream, expectedLastEventID=%d but no more events after eventID=%d",
            expectedLastEventId, processedEventId);
    return new IllegalStateException(message, new StaleWorkflowHistoryException(message));
  }

  static IllegalStateException newIncompleteFullHistory(long firstEventId) {
    String message =
        String.format(
            "Incomplete history for workflow task replay, expected history to start at eventID=1 but got eventID=%d",
            firstEventId);
    return new IllegalStateException(message, new StaleWorkflowHistoryException(message));
  }

  static boolean isStaleWorkflowHistoryFailure(Throwable e) {
    while (e != null) {
      if (e instanceof StaleWorkflowHistoryException) {
        return true;
      }
      e = e.getCause();
    }
    return false;
  }
}
