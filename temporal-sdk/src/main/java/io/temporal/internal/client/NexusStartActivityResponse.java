package io.temporal.internal.client;

import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/**
 * Response returned from starting an activity via {@link NexusStartActivityRequest}. Mirrors {@link
 * NexusStartWorkflowResponse}.
 */
@Experimental
public final class NexusStartActivityResponse {
  private final String activityId;
  private final @Nullable String runId;
  private final String operationToken;

  public NexusStartActivityResponse(
      String activityId, @Nullable String runId, String operationToken) {
    this.activityId = activityId;
    this.runId = runId;
    this.operationToken = operationToken;
  }

  public String getActivityId() {
    return activityId;
  }

  @Nullable
  public String getRunId() {
    return runId;
  }

  public String getOperationToken() {
    return operationToken;
  }
}
