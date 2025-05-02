
package io.temporal.workflow

import kotlin.reflect.javaType
import kotlin.reflect.typeOf

/**
 * @see ChildWorkflowStub.execute
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> ChildWorkflowStub.execute(vararg args: Any?): T {
  return execute(T::class.java, typeOf<T>().javaType, *args)
}

/**
 * @see ChildWorkflowStub.executeAsync
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> ChildWorkflowStub.executeAsync(vararg args: Any?): Promise<T> {
  return executeAsync(T::class.java, typeOf<T>().javaType, *args)
}
