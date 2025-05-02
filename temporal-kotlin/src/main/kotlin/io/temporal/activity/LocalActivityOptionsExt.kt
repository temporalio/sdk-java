
package io.temporal.activity

import io.temporal.common.RetryOptions
import io.temporal.kotlin.TemporalDsl

/**
 * Options used to configure how a local Activity is invoked.
 *
 * @see LocalActivityOptions
 */
inline fun LocalActivityOptions(
  options: @TemporalDsl LocalActivityOptions.Builder.() -> Unit
): LocalActivityOptions {
  return LocalActivityOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [LocalActivityOptions], optionally overriding some of its properties.
 */
inline fun LocalActivityOptions.copy(
  overrides: @TemporalDsl LocalActivityOptions.Builder.() -> Unit
): LocalActivityOptions {
  return toBuilder().apply(overrides).build()
}

/**
 * @see LocalActivityOptions.Builder.setRetryOptions
 * @see LocalActivityOptions.getRetryOptions
 */
inline fun @TemporalDsl LocalActivityOptions.Builder.setRetryOptions(
  retryOptions: @TemporalDsl RetryOptions.Builder.() -> Unit
) {
  setRetryOptions(RetryOptions(retryOptions))
}
