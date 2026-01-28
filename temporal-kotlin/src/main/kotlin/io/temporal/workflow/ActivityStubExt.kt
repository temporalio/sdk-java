
package io.temporal.workflow

import kotlin.reflect.javaType
import kotlin.reflect.typeOf

/**
 * Executes an activity by its type name and arguments. Blocks until the activity completion.
 *
 * @param activityName name of an activity type to execute.
 * @param T the expected return class of the activity. Use `Void` for activities that return void
 * type.
 * @see ActivityStub.execute
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> ActivityStub.execute(activityName: String, vararg args: Any?): T {
  return execute(activityName, T::class.java, typeOf<T>().javaType, *args)
}

/**
 * Executes an activity asynchronously by its type name and arguments.
 *
 * @param activityName name of an activity type to execute.
 * @param T the expected return class of the activity. Use `Void` for activities that return void
 * type.
 * @see ActivityStub.executeAsync
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> ActivityStub.executeAsync(
  activityName: String,
  vararg args: Any?
): Promise<T> {
  return executeAsync(activityName, T::class.java, typeOf<T>().javaType, *args)
}
