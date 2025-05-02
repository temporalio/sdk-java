
package io.temporal.workflow

import io.temporal.kotlin.TemporalDsl

/**
 * Builds an instance of [Saga].
 *
 * ```kotlin
 * val saga = Saga {
 *     // saga options
 * }
 * try {
 *   val r = activity.foo()
 *   saga.addCompensation { activity.cleanupFoo(arg2, r) }
 *   val r2 = Async.function { activity.bar() }
 *   r2.thenApply { r2result ->
 *       saga.addCompensation { activity.cleanupBar(r2result) }
 *   }
 *   // ...
 *   useR2(r2.get())
 * } catch (Exception e) {
 *    saga.compensate()
 *    // Other error handling if needed.
 * }
 * ```
 *
 * @see Saga
 * @see Saga.Options
 */
inline fun Saga(options: @TemporalDsl Saga.Options.Builder.() -> Unit = {}): Saga {
  return Saga(Saga.Options.Builder().apply(options).build())
}
