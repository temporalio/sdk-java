
package io.temporal.serviceclient

import io.temporal.kotlin.TemporalDsl

/**
 * @see WorkflowServiceStubsOptions
 */
inline fun WorkflowServiceStubsOptions(
  options: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit
): WorkflowServiceStubsOptions {
  return WorkflowServiceStubsOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [WorkflowServiceStubsOptions], optionally overriding some of its properties.
 */
inline fun WorkflowServiceStubsOptions.copy(
  overrides: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit
): WorkflowServiceStubsOptions {
  return WorkflowServiceStubsOptions.newBuilder(this).apply(overrides).build()
}
