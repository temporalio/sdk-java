package io.temporal.client;

import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/**
 * Thrown by {@link ActivityClient#start} when the server returns an ALREADY_EXISTS error because an
 * activity with the same ID is already running (or has a completed run that conflicts with the
 * requested {@link StartActivityOptions#getIdReusePolicy()} / {@link
 * StartActivityOptions#getIdConflictPolicy()}).
 */
@Experimental
public final class ActivityAlreadyStartedException extends ActivityException {

  private final String activityType;

  public ActivityAlreadyStartedException(
      String activityId, String activityType, @Nullable String runId, Throwable cause) {
    super(
        "Activity already started: activityId='"
            + activityId
            + "', activityType='"
            + activityType
            + (runId != null ? "', runId='" + runId + "'" : "'"),
        activityId,
        runId,
        cause);
    this.activityType = activityType;
  }

  /** The activity type that was requested. */
  public String getActivityType() {
    return activityType;
  }
}
