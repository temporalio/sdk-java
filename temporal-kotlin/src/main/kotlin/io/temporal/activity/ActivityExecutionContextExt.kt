
package io.temporal.activity

import kotlin.reflect.javaType
import kotlin.reflect.typeOf

/**
 * Extracts heartbeat details from the last heartbeat of the current activity attempt or from the
 * last failed attempt if no heartbeats were sent yet.
 *
 * @param T type of the Heartbeat details
 * @see ActivityExecutionContext.getHeartbeatDetails
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> ActivityExecutionContext.getHeartbeatDetailsOrNull(): T? {
  return getHeartbeatDetails(T::class.java, typeOf<T>().javaType).orElse(null)
}
