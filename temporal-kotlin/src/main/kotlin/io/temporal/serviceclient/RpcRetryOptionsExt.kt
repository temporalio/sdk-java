
package io.temporal.serviceclient

import io.temporal.kotlin.TemporalDsl

/**
 * @see RpcRetryOptions
 */
inline fun RpcRetryOptions(
  options: @TemporalDsl RpcRetryOptions.Builder.() -> Unit
): RpcRetryOptions {
  return RpcRetryOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [RpcRetryOptions], optionally overriding some of its properties.
 */
inline fun RpcRetryOptions.copy(
  overrides: @TemporalDsl RpcRetryOptions.Builder.() -> Unit
): RpcRetryOptions {
  return RpcRetryOptions.newBuilder(this).apply(overrides).build()
}
