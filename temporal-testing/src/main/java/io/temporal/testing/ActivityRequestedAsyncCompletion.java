package io.temporal.testing;

/**
 * Exception thrown when an activity request to complete asynchronously in the {@link
 * TestActivityEnvironment}. Intended to be used in unit tests to assert an activity requested async
 * completion.
 */
public final class ActivityRequestedAsyncCompletion extends RuntimeException {
  private final String activityId;
  private final boolean manualCompletion;

  public ActivityRequestedAsyncCompletion(String activityId, boolean manualCompletion) {
    super("activity requested async completion");
    this.activityId = activityId;
    this.manualCompletion = manualCompletion;
  }

  public String getActivityId() {
    return activityId;
  }

  public boolean isManualCompletion() {
    return manualCompletion;
  }
}
