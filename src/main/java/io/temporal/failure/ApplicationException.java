package io.temporal.failure;

import io.temporal.common.converter.DataConverter;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.failure.ApplicationFailureInfo;
import io.temporal.proto.failure.Failure;
import java.lang.reflect.Type;
import java.util.Optional;

public final class ApplicationException extends RemoteException {
  private final String type;
  private final Optional<Payloads> details;
  private final DataConverter dataConverter;
  private final boolean nonRetryable;

  ApplicationException(Failure failure, DataConverter dataConverter, Exception cause) {
    super(failure, cause);
    if (!failure.hasApplicationFailureInfo()) {
      throw new IllegalArgumentException(
          "Application failure expected: " + failure.getFailureInfoCase());
    }
    ApplicationFailureInfo info = failure.getApplicationFailureInfo();
    this.type = info.getType();
    this.details = info.hasDetails() ? Optional.of(info.getDetails()) : Optional.empty();
    this.dataConverter = dataConverter;
    this.nonRetryable = info.getNonRetryable();
  }

  public String getType() {
    return type;
  }

  Optional<Payloads> getDetails() {
    return details;
  }

  public boolean isNonRetryable() {
    return nonRetryable;
  }

  public <V> V getDetails(Class<V> detailsClass) {
    return getDetails(detailsClass, detailsClass);
  }

  public <V> V getDetails(Class<V> detailsClass, Type detailsType) {
    return dataConverter.fromData(details, detailsClass, detailsType);
  }
}
