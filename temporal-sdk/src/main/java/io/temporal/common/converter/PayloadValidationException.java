package io.temporal.common.converter;

import io.temporal.failure.ApplicationFailure;

/** Factory for failures raised when converting a value that violates its payload schema. */
public final class PayloadValidationException {
  private static final String MESSAGE = "Payload validation failed";
  private static final String TYPE = "PayloadValidationError";

  private PayloadValidationException() {}

  /**
   * Creates a non-retryable failure containing the aggregated validation violations.
   *
   * <p>The violations are stored as a single details value and serialized by the configured {@link
   * DataConverter}.
   *
   * @param violations aggregated payload validation violations
   */
  public static ApplicationFailure create(Object violations) {
    return ApplicationFailure.newNonRetryableFailure(MESSAGE, TYPE, violations);
  }
}
