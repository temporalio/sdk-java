package io.temporal.failure;

import io.temporal.common.converter.DataConverter;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.TimeoutType;
import io.temporal.proto.failure.Failure;
import io.temporal.proto.failure.TimeoutFailureInfo;
import java.lang.reflect.Type;
import java.util.Optional;

public final class TimeoutException extends RemoteException {
  private final TimeoutType timeoutType;
  private final Optional<Payloads> lastHeartbeatDetails;
  private final DataConverter dataConverter;

  TimeoutException(Failure failure, DataConverter dataConverter, Exception cause) {
    super(failure);
    if (!failure.hasTimeoutFailureInfo()) {
      throw new IllegalArgumentException(
          "Timeout failure expected: " + failure.getFailureInfoCase());
    }
    TimeoutFailureInfo info = failure.getTimeoutFailureInfo();
    if (info.hasLastFailure()) {
      initCause(FailureConverter.failureToException(info.getLastFailure(), dataConverter));
      addSuppressed(cause);
    } else {
      initCause(cause);
    }
    this.timeoutType = info.getTimeoutType();
    this.lastHeartbeatDetails =
        info.hasLastHeartbeatDetails()
            ? Optional.of(info.getLastHeartbeatDetails())
            : Optional.empty();
    this.dataConverter = dataConverter;
  }

  public TimeoutType getTimeoutType() {
    return timeoutType;
  }

  Optional<Payloads> getLastHeartbeatDetails() {
    return lastHeartbeatDetails;
  }

  public <V> V getLastHeartbeatDetails(Class<V> detailsClass) {
    return getLastHeartbeatDetails(detailsClass, detailsClass);
  }

  public <V> V getLastHeartbeatDetails(Class<V> detailsClass, Type detailsType) {
    return dataConverter.fromData(lastHeartbeatDetails, detailsClass, detailsType);
  }
}
