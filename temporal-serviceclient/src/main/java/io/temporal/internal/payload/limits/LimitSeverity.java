package io.temporal.internal.payload.limits;

/** Whether a violation exceeded the warning threshold or the error threshold. */
enum LimitSeverity {
  /** Exceeded the warning threshold. */
  WARNING,
  /** Exceeded the error threshold. */
  ERROR
}
