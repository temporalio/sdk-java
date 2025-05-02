
package io.temporal.workflow

import io.temporal.common.RetryOptions
import io.temporal.kotlin.TemporalDsl

/**
 * @see ChildWorkflowOptions
 */
inline fun ChildWorkflowOptions(
  options: @TemporalDsl ChildWorkflowOptions.Builder.() -> Unit
): ChildWorkflowOptions {
  return ChildWorkflowOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [ChildWorkflowOptions], optionally overriding some of its properties.
 */
inline fun ChildWorkflowOptions.copy(
  overrides: @TemporalDsl ChildWorkflowOptions.Builder.() -> Unit
): ChildWorkflowOptions {
  return toBuilder().apply(overrides).build()
}

/**
 * @see ChildWorkflowOptions.Builder.setRetryOptions
 * @see ChildWorkflowOptions.getRetryOptions
 */
inline fun ChildWorkflowOptions.Builder.setRetryOptions(
  retryOptions: @TemporalDsl RetryOptions.Builder.() -> Unit
) {
  setRetryOptions(RetryOptions(retryOptions))
}
