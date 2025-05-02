
package io.temporal.activity

import kotlin.reflect.javaType
import kotlin.reflect.typeOf

/**
 * Extracts Heartbeat details from the last failed attempt.
 *
 * @param T type of the Heartbeat details
 * @see ActivityExecutionContext.getHeartbeatDetails
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> ActivityExecutionContext.getHeartbeatDetailsOrNull(): T? {
  return getHeartbeatDetails(T::class.java, typeOf<T>().javaType).orElse(null)
}
