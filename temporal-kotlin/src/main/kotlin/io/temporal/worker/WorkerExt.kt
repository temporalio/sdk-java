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

package io.temporal.worker

import io.temporal.kotlin.TemporalDsl

/**
 * Registers workflow implementation classes with a worker.
 *
 * @see Worker.registerWorkflowImplementationTypes
 */
inline fun Worker.registerWorkflowImplementationTypes(
  vararg workflowImplementationClasses: Class<*>,
  options: @TemporalDsl WorkflowImplementationOptions.Builder.() -> Unit
) {
  registerWorkflowImplementationTypes(
    WorkflowImplementationOptions(options),
    *workflowImplementationClasses
  )
}

/**
 * Registers a single workflow implementation class with a worker.
 *
 * @param T workflow implementation type to register
 * @see Worker.registerWorkflowImplementationTypes
 */
inline fun <reified T : Any> Worker.registerWorkflowImplementationType() {
  registerWorkflowImplementationTypes(T::class.java)
}

/**
 * Registers a single workflow implementation class with a worker.
 *
 * @param T workflow implementation type to register
 * @see Worker.registerWorkflowImplementationTypes
 */
inline fun <reified T : Any> Worker.registerWorkflowImplementationType(
  options: @TemporalDsl WorkflowImplementationOptions.Builder.() -> Unit
) {
  registerWorkflowImplementationTypes(T::class.java, options = options)
}

/**
 * Configures a factory to use when an instance of a workflow implementation is created.
 *
 * @param T Workflow interface that this factory implements
 * @param factory factory that when called creates a new instance of the workflow implementation
 * object.
 * @see Worker.addWorkflowImplementationFactory
 */
inline fun <reified T : Any> Worker.addWorkflowImplementationFactory(
  options: WorkflowImplementationOptions,
  noinline factory: () -> T
) {
  addWorkflowImplementationFactory(options, T::class.java, factory)
}

/**
 * Configures a factory to use when an instance of a workflow implementation is created.
 *
 * ```kotlin
 * worker.addWorkflowImplementationFactory<ChildWorkflow> {
 *   val child = mock<ChildWorkflow>()
 *   when(child.workflow(anyString(), anyString())).thenReturn("result1")
 *   child
 * }
 * ```
 *
 * @param T Workflow interface that this factory implements
 * @param factory factory that when called creates a new instance of the workflow implementation
 * object.
 * @see Worker.addWorkflowImplementationFactory
 */
inline fun <reified T : Any> Worker.addWorkflowImplementationFactory(
  noinline factory: () -> T
) {
  addWorkflowImplementationFactory(T::class.java, factory)
}
