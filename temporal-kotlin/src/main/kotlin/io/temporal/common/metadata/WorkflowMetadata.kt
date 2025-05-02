
package io.temporal.common.metadata

import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

/**
 * Resolves workflow name by the workflow interface.
 *
 * ```kotlin
 * val workflowName = workflowName(WorkflowInterface::class.java)
 * ```
 */
fun workflowName(workflowClass: Class<*>): String {
  val workflowInterfaceMetadata = POJOWorkflowInterfaceMetadata.newInstance(workflowClass)
  return workflowInterfaceMetadata.workflowType.orElse(null)
    ?: throw IllegalArgumentException("$workflowClass does not define a workflow method")
}

/**
 * Resolves workflow name by the workflow interface.
 *
 * ```kotlin
 * val workflowName = workflowName<WorkflowInterface>()
 * ```
 */
inline fun <reified T : Any> workflowName(): String {
  return workflowName(T::class.java)
}

/**
 * Resolves workflow signal name by the workflow signal method reference.
 *
 * ```kotlin
 * val workflowSignalName = workflowSignalName(WorkflowInterface::signalMethod)
 * ```
 */
fun workflowSignalName(method: KFunction<*>): String {
  return workflowMethodName(method, WorkflowMethodType.SIGNAL)
}

/**
 * Resolves workflow query type by the workflow query method reference.
 *
 * ```kotlin
 * val workflowQueryType = workflowQueryType(WorkflowInterface::queryMethod)
 * ```
 */
fun workflowQueryType(method: KFunction<*>): String {
  return workflowMethodName(method, WorkflowMethodType.QUERY)
}

private fun workflowMethodName(method: KFunction<*>, type: WorkflowMethodType): String {
  val javaMethod = method.javaMethod
    ?: throw IllegalArgumentException("Invalid method reference $method")
  val interfaceMetadata = POJOWorkflowInterfaceMetadata.newInstance(javaMethod.declaringClass)
  val methodMetadata = interfaceMetadata.methodsMetadata.find { it.workflowMethod == javaMethod }
    ?: throw IllegalArgumentException("Not a workflow method reference $method")
  if (methodMetadata.type != type) {
    throw IllegalArgumentException("Workflow method $method is not of expected type $type")
  }
  return methodMetadata.name
}
