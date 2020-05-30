package io.temporal.failure;

import io.temporal.common.converter.DataConverter;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.failure.Failure;
import io.temporal.proto.failure.ResetWorkflowFailureInfo;
import java.lang.reflect.Type;
import java.util.Optional;

public final class ResetWorkflowException extends RemoteException {
  private final Optional<Payloads> lastHeartbeatDetails;
  private final DataConverter dataConverter;

  ResetWorkflowException(Failure failure, DataConverter dataConverter, Exception cause) {
    super(failure);
    if (!failure.hasResetWorkflowFailureInfo()) {
      throw new IllegalArgumentException(
          "Timeout failure expected: " + failure.getFailureInfoCase());
    }
    ResetWorkflowFailureInfo info = failure.getResetWorkflowFailureInfo();
    this.lastHeartbeatDetails =
        info.hasLastHeartbeatDetails()
            ? Optional.of(info.getLastHeartbeatDetails())
            : Optional.empty();
    this.dataConverter = dataConverter;
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
