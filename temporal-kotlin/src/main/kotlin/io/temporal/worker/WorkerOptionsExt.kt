
package io.temporal.worker

import io.temporal.kotlin.TemporalDsl

/**
 * @see WorkerOptions
 */
inline fun WorkerOptions(
  options: @TemporalDsl WorkerOptions.Builder.() -> Unit
): WorkerOptions {
  return WorkerOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [WorkerOptions], optionally overriding some of its properties.
 */
inline fun WorkerOptions.copy(
  overrides: @TemporalDsl WorkerOptions.Builder.() -> Unit
): WorkerOptions {
  return WorkerOptions.newBuilder(this).apply(overrides).build()
}
