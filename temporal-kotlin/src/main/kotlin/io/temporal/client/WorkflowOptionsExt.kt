
package io.temporal.client

import io.temporal.common.RetryOptions
import io.temporal.kotlin.TemporalDsl

/**
 * @see WorkflowOptions
 */
inline fun WorkflowOptions(
  options: @TemporalDsl WorkflowOptions.Builder.() -> Unit
): WorkflowOptions {
  return WorkflowOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [WorkflowOptions], optionally overriding some of its properties.
 */
inline fun WorkflowOptions.copy(
  overrides: @TemporalDsl WorkflowOptions.Builder.() -> Unit
): WorkflowOptions {
  return toBuilder().apply(overrides).build()
}

/**
 * @see WorkflowOptions.Builder.setRetryOptions
 * @see WorkflowOptions.getRetryOptions
 */
inline fun WorkflowOptions.Builder.setRetryOptions(
  retryOptions: @TemporalDsl RetryOptions.Builder.() -> Unit
) {
  setRetryOptions(RetryOptions(retryOptions))
}
