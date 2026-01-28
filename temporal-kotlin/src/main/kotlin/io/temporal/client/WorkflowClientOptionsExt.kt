
package io.temporal.client

import io.temporal.kotlin.TemporalDsl

/**
 * Options for [WorkflowClient] configuration.
 *
 * @see WorkflowClientOptions
 */
inline fun WorkflowClientOptions(
  options: @TemporalDsl WorkflowClientOptions.Builder.() -> Unit
): WorkflowClientOptions {
  return WorkflowClientOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [WorkflowClientOptions], optionally overriding some of its properties.
 */
inline fun WorkflowClientOptions.copy(
  overrides: @TemporalDsl WorkflowClientOptions.Builder.() -> Unit
): WorkflowClientOptions {
  return toBuilder().apply(overrides).build()
}
