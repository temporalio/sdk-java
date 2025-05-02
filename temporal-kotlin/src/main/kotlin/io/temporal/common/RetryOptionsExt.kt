
package io.temporal.common

import io.temporal.kotlin.TemporalDsl

/**
 * @see RetryOptions
 */
inline fun RetryOptions(
  options: @TemporalDsl RetryOptions.Builder.() -> Unit
): RetryOptions {
  return RetryOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [RetryOptions], optionally overriding some of its properties.
 */
inline fun RetryOptions.copy(
  overrides: @TemporalDsl RetryOptions.Builder.() -> Unit
): RetryOptions {
  return toBuilder().apply(overrides).build()
}
