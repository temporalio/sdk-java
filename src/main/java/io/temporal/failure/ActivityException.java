package io.temporal.failure;

import io.temporal.proto.failure.ActivityTaskFailureInfo;
import io.temporal.proto.failure.Failure;

public final class ActivityException extends RemoteException {

  private final long scheduledEventId;
  private final long startedEventId;
  private final String identity;

  public ActivityException(Failure failure, Exception cause) {
    super(failure, cause);
    if (!failure.hasActivityTaskFailureInfo()) {
      throw new IllegalArgumentException(
          "Activity failure expected: " + failure.getFailureInfoCase());
    }
    ActivityTaskFailureInfo info = failure.getActivityTaskFailureInfo();
    this.scheduledEventId = info.getScheduledEventId();
    this.startedEventId = info.getStartedEventId();
    this.identity = info.getIdentity();
  }

  public long getScheduledEventId() {
    return scheduledEventId;
  }

  public long getStartedEventId() {
    return startedEventId;
  }

  public String getIdentity() {
    return identity;
  }
}
