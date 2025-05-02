package io.temporal.failure;

/**
 * Used to categorize application failures, for example, to distinguish benign errors from others.
 *
 * @see io.temporal.api.enums.v1.ApplicationErrorCategory
 */
public enum ApplicationErrorCategory {
  UNSPECIFIED,
  /** Expected application error with little/no severity. */
  BENIGN,
}
