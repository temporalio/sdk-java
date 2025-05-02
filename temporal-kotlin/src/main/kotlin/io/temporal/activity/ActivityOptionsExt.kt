
package io.temporal.activity

import io.temporal.common.RetryOptions
import io.temporal.kotlin.TemporalDsl

/**
 * Options used to configure how an Activity is invoked.
 *
 * @see ActivityOptions
 */
inline fun ActivityOptions(
  options: @TemporalDsl ActivityOptions.Builder.() -> Unit
): ActivityOptions {
  return ActivityOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [ActivityOptions], optionally overriding some of its properties.
 */
inline fun ActivityOptions.copy(
  overrides: @TemporalDsl ActivityOptions.Builder.() -> Unit
): ActivityOptions {
  return toBuilder().apply(overrides).build()
}

/**
 * @see ActivityOptions.Builder.setRetryOptions
 * @see ActivityOptions.getRetryOptions
 */
inline fun ActivityOptions.Builder.setRetryOptions(
  retryOptions: @TemporalDsl RetryOptions.Builder.() -> Unit
) {
  setRetryOptions(RetryOptions(retryOptions))
}
