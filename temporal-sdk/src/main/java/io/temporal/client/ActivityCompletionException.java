package io.temporal.client;

import io.temporal.activity.ActivityInfo;
import io.temporal.failure.TemporalException;
import java.util.Optional;

/** Base exception for all failures returned by an activity completion client. Do not extend! */
public class ActivityCompletionException extends TemporalException {

  private final String workflowId;

  private final String runId;

  private final String activityType;

  private final String activityId;

  protected ActivityCompletionException(ActivityInfo info) {
    this(info, null);
  }

  protected ActivityCompletionException(ActivityInfo info, Throwable cause) {
    super(
        info != null
            ? "WorkflowId="
                + info.getWorkflowId()
                + ", RunId="
                + info.getRunId()
                + ", ActivityType="
                + info.getActivityType()
                + ", ActivityId="
                + info.getActivityId()
            : null,
        cause);
    if (info != null) {
      workflowId = info.getWorkflowId();
      runId = info.getRunId();
      activityType = info.getActivityType();
      activityId = info.getActivityId();
    } else {
      this.workflowId = null;
      this.runId = null;
      activityType = null;
      activityId = null;
    }
  }

  protected ActivityCompletionException(String activityId, Throwable cause) {
    super("ActivityId=" + activityId, cause);
    this.workflowId = null;
    this.runId = null;
    this.activityType = null;
    this.activityId = activityId;
  }

  protected ActivityCompletionException(Throwable cause) {
    this((ActivityInfo) null, cause);
  }

  protected ActivityCompletionException() {
    super(null, null);
    workflowId = null;
    runId = null;
    activityType = null;
    activityId = null;
  }

  /** Optional as it might be not known to the exception source. */
  public Optional<String> getWorkflowId() {
    return Optional.ofNullable(workflowId);
  }

  /** Optional as it might be not known to the exception source. */
  public Optional<String> getRunId() {
    return Optional.ofNullable(runId);
  }

  /** Optional as it might be not known to the exception source. */
  public Optional<String> getActivityType() {
    return Optional.ofNullable(activityType);
  }

  /** Optional as it might be not known to the exception source. */
  public Optional<String> getActivityId() {
    return Optional.ofNullable(activityId);
  }
}
