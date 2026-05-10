package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.failure.TemporalException;
import javax.annotation.Nullable;

/** Base exception for standalone activity execution failures. */
@Experimental
public abstract class ActivityException extends TemporalException {

  private final String activityId;
  private final @Nullable String runId;

  protected ActivityException(
      String message, String activityId, @Nullable String runId, @Nullable Throwable cause) {
    super(message, cause);
    this.activityId = activityId;
    this.runId = runId;
  }

  /** The ID of the activity execution that caused this exception. */
  public String getActivityId() {
    return activityId;
  }

  /** The run ID of the activity execution, or {@code null} if not available. */
  @Nullable
  public String getRunId() {
    return runId;
  }
}
