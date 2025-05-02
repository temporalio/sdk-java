
package io.temporal.worker

import io.temporal.kotlin.TemporalDsl

/**
 * @see WorkerFactoryOptions
 */
inline fun WorkerFactoryOptions(
  options: @TemporalDsl WorkerFactoryOptions.Builder.() -> Unit
): WorkerFactoryOptions {
  return WorkerFactoryOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [WorkerFactoryOptions], optionally overriding some of its properties.
 */
inline fun WorkerFactoryOptions.copy(
  overrides: @TemporalDsl WorkerFactoryOptions.Builder.() -> Unit
): WorkerFactoryOptions {
  return toBuilder().apply(overrides).build()
}
