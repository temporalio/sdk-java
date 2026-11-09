package io.temporal.internal.payload.limits;

import com.google.protobuf.Message;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates an outbound request message's payload/memo fields against a set of {@link
 * PayloadLimits}, mirroring the size checks the Temporal server enforces.
 *
 * <p>The per-(message, field) policy is generated from the proto descriptors against the
 * hand-authored {@code *_FIELDS} tables in {@code PayloadLimitValidatorGenerator}; adding or
 * removing a payload-bearing field fails the build until the tables are updated.
 */
final class PayloadLimitValidator {
  private static final Logger log = LoggerFactory.getLogger(PayloadLimitValidator.class);

  private PayloadLimitValidator() {}

  /**
   * Validates {@code request} against {@code limits}.
   *
   * <p>If any field exceeded its error threshold, logs the error(s) and returns the first one
   * without logging warnings; otherwise logs each warning and returns empty. With no error
   * thresholds set (or a request type that carries no validated payload fields), this only warns
   * and always returns empty.
   */
  static Optional<PayloadLimitViolation> validate(Message request, PayloadLimits limits) {
    CollectingSink sink = new CollectingSink(limits);
    GeneratedPayloadLimitValidator.dispatch(sink, request);

    List<PayloadLimitViolation> errors = sink.getErrors();
    if (!errors.isEmpty()) {
      for (PayloadLimitViolation e : errors) {
        log.error(
            "{} (size={}, limit={}, path={})",
            e.getMessage(),
            e.getSize(),
            e.getLimit(),
            e.getPath());
      }
      return Optional.of(errors.get(0));
    }
    for (PayloadLimitViolation w : sink.getWarnings()) {
      log.warn(
          "{} (size={}, limit={}, path={})",
          w.getMessage(),
          w.getSize(),
          w.getLimit(),
          w.getPath());
    }
    return Optional.empty();
  }
}
