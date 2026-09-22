package io.temporal.common.converter;

import io.temporal.failure.ApplicationFailure;

/** Factory for failures raised when converting a value that violates its payload schema. */
public final class PayloadValidationException {
  private static final String MESSAGE = "Payload validation failed";
  private static final String TYPE = "PayloadValidationError";

  private PayloadValidationException() {}

  /**
   * Creates a non-retryable failure containing payload validation details.
   *
   * <p>Non-null details are stored as a single value and serialized by the configured {@link
   * DataConverter}. If details are null, the failure has no detail values.
   *
   * @param details payload validation details
   */
  public static ApplicationFailure newPayloadValidationException(Object details) {
    if (details == null) {
      return ApplicationFailure.newNonRetryableFailure(MESSAGE, TYPE);
    }
    return ApplicationFailure.newNonRetryableFailure(MESSAGE, TYPE, details);
  }
}
