package io.temporal.internal.payload.limits;

/**
 * Warn/error thresholds (bytes) for both limit classes. A {@code 0} threshold disables that check
 * for that class: {@code 0} warn = no warnings, {@code 0} error = no error enforcement (warnings
 * only).
 */
final class PayloadLimits {
  private final long blobWarn;
  private final long blobError;
  private final long memoWarn;
  private final long memoError;

  PayloadLimits(long blobWarn, long blobError, long memoWarn, long memoError) {
    this.blobWarn = blobWarn;
    this.blobError = blobError;
    this.memoWarn = memoWarn;
    this.memoError = memoError;
  }

  /** All thresholds disabled ({@code 0}). */
  static PayloadLimits none() {
    return new PayloadLimits(0, 0, 0, 0);
  }

  /** The warning threshold for {@code clazz}; {@code 0} means disabled. */
  long warn(LimitClass clazz) {
    return clazz == LimitClass.BLOB ? blobWarn : memoWarn;
  }

  /** The error threshold for {@code clazz}; {@code 0} means disabled. */
  long error(LimitClass clazz) {
    return clazz == LimitClass.BLOB ? blobError : memoError;
  }
}
