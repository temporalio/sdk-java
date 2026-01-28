
package io.temporal.common.metadata

import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

/**
 * Resolves activity name by the activity method reference.
 *
 * ```kotlin
 * val activityName = activityName(ActivityInterface::activityMethod)
 * ```
 */
fun activityName(method: KFunction<*>): String {
  val javaMethod = method.javaMethod
    ?: throw IllegalArgumentException("Invalid method reference $method")
  val interfaceMetadata = POJOActivityInterfaceMetadata.newInstance(javaMethod.declaringClass)
  val methodMetadata = interfaceMetadata.methodsMetadata.find { it.method == javaMethod }
    ?: throw IllegalArgumentException("Not an activity method reference $method")
  return methodMetadata.activityTypeName
}
