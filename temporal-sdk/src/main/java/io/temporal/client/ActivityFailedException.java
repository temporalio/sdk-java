package io.temporal.client;

import javax.annotation.Nullable;

/**
 * Thrown by {@link ActivityHandle#getResult()} when the standalone activity was not successful. The
 * original cause can be retrieved via {@link #getCause()}.
 */
public final class ActivityFailedException extends ActivityException {

  public ActivityFailedException(
      String message, String activityId, @Nullable String runId, @Nullable Throwable cause) {
    super(message, activityId, runId, cause);
  }
}
