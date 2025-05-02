
package io.temporal.client

import java.util.concurrent.CompletableFuture
import kotlin.reflect.javaType
import kotlin.reflect.typeOf

/**
 * Returns workflow result potentially waiting for workflow to complete.
 *
 * @param T type of the workflow return value
 * @see WorkflowStub.getResult
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> WorkflowStub.getResult(): T {
  return getResult(T::class.java, typeOf<T>().javaType)
}

/**
 * @see WorkflowStub.getResultAsync
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> WorkflowStub.getResultAsync(): CompletableFuture<T> {
  return getResultAsync(T::class.java, typeOf<T>().javaType)
}
