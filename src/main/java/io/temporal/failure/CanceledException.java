package io.temporal.failure;

import io.temporal.common.converter.DataConverter;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.failure.CanceledFailureInfo;
import io.temporal.proto.failure.Failure;
import java.lang.reflect.Type;
import java.util.Optional;

public final class CanceledException extends RemoteException {
  private final Optional<Payloads> details;
  private final DataConverter dataConverter;

  CanceledException(Failure failure, DataConverter dataConverter, Exception cause) {
    super(failure, cause);
    if (!failure.hasCanceledFailureInfo()) {
      throw new IllegalArgumentException(
          "Canceled failure expected: " + failure.getCanceledFailureInfo());
    }
    CanceledFailureInfo info = failure.getCanceledFailureInfo();
    this.details = info.hasDetails() ? Optional.of(info.getDetails()) : Optional.empty();
    this.dataConverter = dataConverter;
  }

  Optional<Payloads> getDetails() {
    return details;
  }

  public <V> V getDetails(Class<V> detailsClass) {
    return getDetails(detailsClass, detailsClass);
  }

  public <V> V getDetails(Class<V> detailsClass, Type detailsType) {
    return dataConverter.fromData(details, detailsClass, detailsType);
  }
}
