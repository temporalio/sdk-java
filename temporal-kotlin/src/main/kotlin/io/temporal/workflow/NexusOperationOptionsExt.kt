
package io.temporal.workflow

import io.temporal.kotlin.TemporalDsl

/**
 * @see NexusOperationOptions
 */
inline fun NexusOperationOptions(
  options: @TemporalDsl NexusOperationOptions.Builder.() -> Unit
): NexusOperationOptions {
  return NexusOperationOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [NexusOperationOptions], optionally overriding some of its properties.
 */
inline fun NexusOperationOptions.copy(
  overrides: @TemporalDsl NexusOperationOptions.Builder.() -> Unit
): NexusOperationOptions {
  return toBuilder().apply(overrides).build()
}
