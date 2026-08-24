package io.temporal.internal.client;

import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;

/**
 * Container class to deduplicate {@link RecordActivityTaskHeartbeatByIdResponse} and {@link
 * RecordActivityTaskHeartbeatResponse}.
 */
public final class ActivityHeartbeatResponse {
  private final boolean cancelRequested;
  private final boolean activityReset;
  private final boolean activityPaused;

  ActivityHeartbeatResponse(
      boolean cancelRequested, boolean activityReset, boolean activityPaused) {
    this.cancelRequested = cancelRequested;
    this.activityReset = activityReset;
    this.activityPaused = activityPaused;
  }

  public boolean getCancelRequested() {
    return cancelRequested;
  }

  public boolean getActivityReset() {
    return activityReset;
  }

  public boolean getActivityPaused() {
    return activityPaused;
  }
}
