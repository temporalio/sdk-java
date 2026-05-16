package io.temporal.nexus;

import io.temporal.common.Experimental;
import java.util.Objects;

/**
 * Input to {@link TemporalOperationHandler#cancelActivityExecution} describing the activity
 * execution to cancel.
 */
@Experimental
public final class CancelActivityExecutionInput {

  private final String activityId;

  public CancelActivityExecutionInput(String activityId) {
    this.activityId = Objects.requireNonNull(activityId);
  }

  /** Returns the activity ID extracted from the operation token. */
  public String getActivityId() {
    return activityId;
  }
}
