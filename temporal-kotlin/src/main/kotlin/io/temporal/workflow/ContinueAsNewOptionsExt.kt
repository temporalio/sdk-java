
package io.temporal.workflow

import io.temporal.kotlin.TemporalDsl

/**
 * @see ContinueAsNewOptions
 */
inline fun ContinueAsNewOptions(
  options: @TemporalDsl ContinueAsNewOptions.Builder.() -> Unit
): ContinueAsNewOptions {
  return ContinueAsNewOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [ContinueAsNewOptions], optionally overriding some of its properties.
 */
inline fun ContinueAsNewOptions.copy(
  overrides: @TemporalDsl ContinueAsNewOptions.Builder.() -> Unit
): ContinueAsNewOptions {
  return ContinueAsNewOptions.newBuilder(this).apply(overrides).build()
}
