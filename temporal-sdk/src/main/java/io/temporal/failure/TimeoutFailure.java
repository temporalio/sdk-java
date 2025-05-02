package io.temporal.failure;

import com.google.common.base.Strings;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.converter.Values;

/** <b>This exception is expected to be thrown only by the Temporal framework code.</b> */
public final class TimeoutFailure extends TemporalFailure {
  private final Values lastHeartbeatDetails;
  private final TimeoutType timeoutType;

  public TimeoutFailure(String message, Object lastHeartbeatDetails, TimeoutType timeoutType) {
    this(message, new EncodedValues(lastHeartbeatDetails), timeoutType, null);
  }

  TimeoutFailure(
      String message, Values lastHeartbeatDetails, TimeoutType timeoutType, Throwable cause) {
    super(getMessage(message, timeoutType), message, cause);
    this.lastHeartbeatDetails = lastHeartbeatDetails;
    this.timeoutType = timeoutType;
  }

  public Values getLastHeartbeatDetails() {
    return lastHeartbeatDetails;
  }

  public TimeoutType getTimeoutType() {
    return timeoutType;
  }

  @Override
  public void setDataConverter(DataConverter converter) {
    ((EncodedValues) lastHeartbeatDetails).setDataConverter(converter);
  }

  public static String getMessage(String message, TimeoutType timeoutType) {
    return (Strings.isNullOrEmpty(message) ? "" : "message='" + message + "', ")
        + "timeoutType="
        + timeoutType;
  }
}
