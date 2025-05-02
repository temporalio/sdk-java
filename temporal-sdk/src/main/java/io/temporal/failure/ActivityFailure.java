package io.temporal.failure;

import io.temporal.api.enums.v1.RetryState;

/**
 * Contains information about an activity failure. Always contains the original reason for the
 * failure as its cause. For example if an activity timed out the cause is {@link TimeoutFailure}.
 *
 * <p><b>This exception is expected to be thrown only by the Temporal framework code.</b>
 */
public final class ActivityFailure extends TemporalFailure {

  private final long scheduledEventId;
  private final long startedEventId;
  private final String activityType;
  private final String activityId;
  private final String identity;
  private final RetryState retryState;

  public ActivityFailure(
      String message,
      long scheduledEventId,
      long startedEventId,
      String activityType,
      String activityId,
      RetryState retryState,
      String identity,
      Throwable cause) {
    super(
        getMessage(
            message,
            scheduledEventId,
            startedEventId,
            activityType,
            activityId,
            retryState,
            identity),
        message,
        cause);
    this.scheduledEventId = scheduledEventId;
    this.startedEventId = startedEventId;
    this.activityType = activityType;
    this.activityId = activityId;
    this.identity = identity;
    this.retryState = retryState;
  }

  public long getScheduledEventId() {
    return scheduledEventId;
  }

  public long getStartedEventId() {
    return startedEventId;
  }

  public String getActivityType() {
    return activityType;
  }

  public String getActivityId() {
    return activityId;
  }

  public String getIdentity() {
    return identity;
  }

  public RetryState getRetryState() {
    return retryState;
  }

  public static String getMessage(
      String originalMessage,
      long scheduledEventId,
      long startedEventId,
      String activityType,
      String activityId,
      RetryState retryState,
      String identity) {
    return "Activity with activityType='"
        + activityType
        + "' failed: '"
        + originalMessage
        + "'. "
        + "scheduledEventId="
        + scheduledEventId
        + (startedEventId == -1 ? "" : ", startedEventId=" + startedEventId)
        + (activityId == null ? "" : ", activityId=" + activityId)
        + ", identity='"
        + identity
        + "', retryState="
        + retryState;
  }
}
