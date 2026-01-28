
package io.temporal.common.converter

import io.temporal.api.common.v1.Payload
import io.temporal.api.common.v1.Payloads
import java.util.Optional
import kotlin.reflect.javaType
import kotlin.reflect.typeOf

/**
 * @see DataConverter.toPayload
 */
fun DataConverter.toPayloadOrNull(value: Any?): Payload? {
  return toPayload(value).orElse(null)
}

/**
 * @param T type of the object
 * @see DataConverter.fromPayload
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> DataConverter.fromPayload(payload: Payload): T? {
  return fromPayload(payload, T::class.java, typeOf<T>().javaType)
}

/**
 * @see DataConverter.toPayloads
 */
fun DataConverter.toPayloadsOrNull(vararg values: Any?): Payloads? {
  return toPayloads(*values).orElse(null)
}

/**
 * Implements conversion of an array of values of different types. Useful for deserializing
 * arguments of function invocations.
 *
 * @param index index of the value in the payloads
 * @param content serialized value to convert to Java objects.
 * @param T type of the parameter stored in the [content]
 * @see DataConverter.fromPayloads
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> DataConverter.fromPayloads(index: Int, content: Payloads?): T? {
  return fromPayloads(index, Optional.ofNullable(content), T::class.java, typeOf<T>().javaType)
}
