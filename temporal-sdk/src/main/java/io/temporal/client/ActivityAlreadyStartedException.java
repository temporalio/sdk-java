package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.failure.TemporalException;
import javax.annotation.Nullable;

/**
 * Thrown by {@link ActivityClient#start} when the server returns an ALREADY_EXISTS error because an
 * activity with the same ID is already running (or has a completed run that conflicts with the
 * requested {@link StartActivityOptions#getIdReusePolicy()} / {@link
 * StartActivityOptions#getIdConflictPolicy()}).
 */
@Experimental
public final class ActivityAlreadyStartedException extends TemporalException {

  private final String activityId;
  private final String activityType;
  private final @Nullable String runId;

  public ActivityAlreadyStartedException(
      String activityId, String activityType, @Nullable String runId, Throwable cause) {
    super(
        "Activity already started: activityId='"
            + activityId
            + "', activityType='"
            + activityType
            + (runId != null ? "', runId='" + runId + "'" : "'"),
        cause);
    this.activityId = activityId;
    this.activityType = activityType;
    this.runId = runId;
  }

  /** The activity ID that was already in use. */
  public String getActivityId() {
    return activityId;
  }

  /** The activity type that was requested. */
  public String getActivityType() {
    return activityType;
  }

  /**
   * The run ID of the existing activity execution, if available. May be null if the server does not
   * provide it.
   */
  @Nullable
  public String getRunId() {
    return runId;
  }
}
