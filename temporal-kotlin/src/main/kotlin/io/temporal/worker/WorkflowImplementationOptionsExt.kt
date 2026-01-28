
package io.temporal.worker

import io.temporal.activity.ActivityOptions
import io.temporal.activity.LocalActivityOptions
import io.temporal.common.metadata.activityName
import io.temporal.kotlin.TemporalDsl

/**
 * @see WorkflowImplementationOptions
 */
inline fun WorkflowImplementationOptions(
  options: @TemporalDsl WorkflowImplementationOptions.Builder.() -> Unit
): WorkflowImplementationOptions {
  return WorkflowImplementationOptions.newBuilder().apply(options).build()
}

/**
 * Set individual Activity options per `activityType`.
 *
 * The [activityName] method could be used resolve activity method references to activity names:
 *
 * ```kotlin
 * val options = WorkflowImplementationOptions {
 *   // ...
 *   setActivityOptions(
 *     activityName(Activity1::method1) to ActivityOptions {
 *       // options for activity method1
 *     },
 *     activityName(Activity2::method2) to ActivityOptions {
 *       // options for activity method2
 *     },
 *   )
 * }
 * ```
 *
 * @param activityOptions map from activityType to [ActivityOptions]
 * @see WorkflowImplementationOptions.Builder.setActivityOptions
 * @see WorkflowImplementationOptions.getActivityOptions
 */
fun WorkflowImplementationOptions.Builder.setActivityOptions(
  vararg activityOptions: Pair<String, ActivityOptions>
) {
  setActivityOptions(activityOptions.toMap())
}

/**
 * @see WorkflowImplementationOptions.Builder.setDefaultActivityOptions
 * @see WorkflowImplementationOptions.getDefaultActivityOptions
 */
inline fun @TemporalDsl WorkflowImplementationOptions.Builder.setDefaultActivityOptions(
  defaultActivityOptions: @TemporalDsl ActivityOptions.Builder.() -> Unit
) {
  setDefaultActivityOptions(ActivityOptions(defaultActivityOptions))
}

/**
 * Set individual Local Activity options per `activityType`.
 *
 * The [activityName] method could be used resolve activity method references to activity names:
 *
 * ```kotlin
 * val options = WorkflowImplementationOptions {
 *   // ...
 *   setLocalActivityOptions(
 *     localActivityName(Activity1::method1) to LocalActivityOptions {
 *       // options for local activity method1
 *     },
 *     localActivityName(Activity2::method2) to LocalActivityOptions {
 *       // options for local activity method2
 *     },
 *   )
 * }
 * ```
 *
 * @param localActivityOptions map from activityType to [LocalActivityOptions]
 * @see WorkflowImplementationOptions.Builder.setLocalActivityOptions
 * @see WorkflowImplementationOptions.getLocalActivityOptions
 */
fun WorkflowImplementationOptions.Builder.setLocalActivityOptions(
  vararg localActivityOptions: Pair<String, LocalActivityOptions>
) {
  setLocalActivityOptions(localActivityOptions.toMap())
}

/**
 * @see WorkflowImplementationOptions.Builder.setDefaultLocalActivityOptions
 * @see WorkflowImplementationOptions.getDefaultLocalActivityOptions
 */
inline fun @TemporalDsl WorkflowImplementationOptions.Builder.setDefaultLocalActivityOptions(
  defaultLocalActivityOptions: @TemporalDsl LocalActivityOptions.Builder.() -> Unit
) {
  setDefaultLocalActivityOptions(LocalActivityOptions(defaultLocalActivityOptions))
}
