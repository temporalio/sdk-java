package io.temporal.client;

import io.temporal.activity.ActivityInfo;

/** Unexpected failure when completing an activity. */
public final class ActivityCompletionFailureException extends ActivityCompletionException {

  public ActivityCompletionFailureException(Throwable cause) {
    super(cause);
  }

  public ActivityCompletionFailureException(ActivityInfo info, Throwable cause) {
    super(info, cause);
  }

  public ActivityCompletionFailureException(String activityId, Throwable cause) {
    super(activityId, cause);
  }
}
