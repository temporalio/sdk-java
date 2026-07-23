package io.temporal.client

import io.temporal.kotlin.TemporalDsl

/**
 * Options for [ActivityClient] configuration.
 *
 * @see ActivityClientOptions
 */
inline fun ActivityClientOptions(
  options: @TemporalDsl ActivityClientOptions.Builder.() -> Unit
): ActivityClientOptions {
  return ActivityClientOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [ActivityClientOptions], optionally overriding some of its properties.
 */
inline fun ActivityClientOptions.copy(
  overrides: @TemporalDsl ActivityClientOptions.Builder.() -> Unit
): ActivityClientOptions {
  return toBuilder().apply(overrides).build()
}
