
package io.temporal.kotlin

/**
 * [Scope control](https://kotlinlang.org/docs/type-safe-builders.html#scope-control-dslmarker)
 * marker annotation for DSL-like code blocks that target Temporal SDK classes.
 */
@DslMarker
@Target(AnnotationTarget.TYPE, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class TemporalDsl
