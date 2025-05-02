
package io.temporal.workflow

import io.temporal.kotlin.TemporalDsl

/**
 * @see NexusServiceOptions
 */
inline fun NexusServiceOptions(
  options: @TemporalDsl NexusServiceOptions.Builder.() -> Unit
): NexusServiceOptions {
  return NexusServiceOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [NexusServiceOptions], optionally overriding some of its properties.
 */
inline fun NexusServiceOptions.copy(
  overrides: @TemporalDsl NexusServiceOptions.Builder.() -> Unit
): NexusServiceOptions {
  return toBuilder().apply(overrides).build()
}
