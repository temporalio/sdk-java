package io.temporal.failure;

import io.temporal.api.failure.v1.Failure;
import io.temporal.common.converter.DataConverter;
import java.util.Optional;

/**
 * Represents failures that can cross workflow and activity boundaries.
 *
 * <p>Only exceptions that extend this class will be propagated to the caller.
 *
 * <p><b>Never extend this class or any of its derivatives. Don't throw any subtype of this class
 * except {@link ApplicationFailure}.</b> They are to be used by the SDK code only.
 *
 * <p>Throw an instance {@link ApplicationFailure} to pass application specific errors between
 * workflows and activities.
 *
 * <p>Any unhandled exception thrown by an activity or workflow will be converted to an instance of
 * {@link ApplicationFailure}.
 */
public abstract class TemporalFailure extends TemporalException {
  private Optional<Failure> failure = Optional.empty();
  private final String originalMessage;

  TemporalFailure(String message, String originalMessage, Throwable cause) {
    super(message, cause);
    this.originalMessage = originalMessage;
  }

  Optional<Failure> getFailure() {
    return failure;
  }

  void setFailure(Failure failure) {
    this.failure = Optional.of(failure);
  }

  public String getOriginalMessage() {
    return originalMessage == null ? "" : originalMessage;
  }

  public void setDataConverter(DataConverter converter) {}
}
