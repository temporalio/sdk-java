package io.temporal.failure;

import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.converter.Values;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** <b>This exception is expected to be thrown only by the Temporal framework code.</b> */
public final class CanceledFailure extends TemporalFailure {
  private final Values details;

  public CanceledFailure(String message, @Nonnull Values details, @Nullable Throwable cause) {
    super(message, message, cause);
    this.details = Objects.requireNonNull(details);
  }

  public CanceledFailure(String message, Object details) {
    this(message, new EncodedValues(details), null);
  }

  public CanceledFailure(String message) {
    this(message, null);
  }

  public Values getDetails() {
    return details;
  }

  @Override
  public void setDataConverter(DataConverter converter) {
    ((EncodedValues) details).setDataConverter(converter);
  }
}
