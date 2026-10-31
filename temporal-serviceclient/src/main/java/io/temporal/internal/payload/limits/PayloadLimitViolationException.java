package io.temporal.internal.payload.limits;

import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Signals that a call failed locally because its request exceeds an error limit; it is the cause of
 * the call's {@code INVALID_ARGUMENT} status. Workers look for it with {@link #find(Throwable)} to
 * replace the rejected completion with a task failure.
 */
public final class PayloadLimitViolationException extends RuntimeException {
  /** A worker fails a task with an {@code ApplicationFailure} of this type on a violation. */
  public static final String FAILURE_TYPE = "PayloadsTooLarge";

  private static final int MAX_CAUSE_DEPTH = 32;

  private final PayloadLimitViolation violation;

  public PayloadLimitViolationException(PayloadLimitViolation violation) {
    // The violation is reported through the task failure, so a stack trace adds nothing.
    super(violation.getMessage(), null, false, false);
    this.violation = violation;
  }

  public PayloadLimitViolation getViolation() {
    return violation;
  }

  /** Returns the violation that caused {@code throwable}, searching its cause chain. */
  public static Optional<PayloadLimitViolation> find(@Nullable Throwable throwable) {
    Throwable t = throwable;
    for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (t instanceof PayloadLimitViolationException) {
        return Optional.of(((PayloadLimitViolationException) t).violation);
      }
      t = t.getCause();
    }
    return Optional.empty();
  }
}
