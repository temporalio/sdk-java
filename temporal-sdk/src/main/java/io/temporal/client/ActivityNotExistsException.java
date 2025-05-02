package io.temporal.client;

import io.temporal.activity.ActivityInfo;

/**
 * Usually indicates that activity was already completed (duplicated request to complete) or timed
 * out or workflow execution is closed (cancelled, terminated or timed out).
 */
public final class ActivityNotExistsException extends ActivityCompletionException {

  public ActivityNotExistsException(Throwable cause) {
    super(cause);
  }

  public ActivityNotExistsException(ActivityInfo info, Throwable cause) {
    super(info, cause);
  }

  public ActivityNotExistsException(String activityId, Throwable cause) {
    super(activityId, cause);
  }
}
