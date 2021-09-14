/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

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
