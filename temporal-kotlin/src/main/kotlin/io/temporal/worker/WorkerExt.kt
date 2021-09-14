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
import io.temporal.workflow.DynamicWorkflow
import io.temporal.workflow.WorkflowMethod

/**
 * Registers workflow implementation classes with a worker. Can be called multiple times to add
 * more types. A workflow implementation class must implement at least one interface with a method
 * annotated with [WorkflowMethod]. By default the short name of the interface is used as a workflow
 * type that this worker supports.
 *
 * Implementations that share a worker must implement different interfaces as a workflow type
 * is identified by the workflow interface, not by the implementation.
 *
 * Use [DynamicWorkflow] implementation to implement many workflow types dynamically. It can be
 * useful for implementing DSL based workflows. Only a single type that implements `DynamicWorkflow`
 * can be registered per worker.
 */
inline fun Worker.registerWorkflowImplementationTypes(
  vararg workflowImplementationClasses: Class<*>,
  options: @TemporalDsl WorkflowImplementationOptions.Builder.() -> Unit,
) {
  registerWorkflowImplementationTypes(
    WorkflowImplementationOptions(options),
    *workflowImplementationClasses
  )
}

/**
 * Registers single workflow implementation class with a worker. Can be called multiple times to add
 * more types. A workflow implementation class must implement at least one interface with a method
 * annotated with [WorkflowMethod]. By default the short name of the interface is used as a workflow
 * type that this worker supports.
 *
 * Implementations that share a worker must implement different interfaces as a workflow type
 * is identified by the workflow interface, not by the implementation.
 *
 * Use [DynamicWorkflow] implementation to implement many workflow types dynamically. It can be
 * useful for implementing DSL based workflows. Only a single type that implements `DynamicWorkflow`
 * can be registered per worker.
 */
inline fun <reified T : Any> Worker.registerWorkflowImplementationType() {
  registerWorkflowImplementationTypes(T::class.java)
}

/**
 * Registers single workflow implementation class with a worker. Can be called multiple times to add
 * more types. A workflow implementation class must implement at least one interface with a method
 * annotated with [WorkflowMethod]. By default the short name of the interface is used as a workflow
 * type that this worker supports.
 *
 * Implementations that share a worker must implement different interfaces as a workflow type
 * is identified by the workflow interface, not by the implementation.
 *
 * Use [DynamicWorkflow] implementation to implement many workflow types dynamically. It can be
 * useful for implementing DSL based workflows. Only a single type that implements `DynamicWorkflow`
 * can be registered per worker.
 */
inline fun <reified T : Any> Worker.registerWorkflowImplementationType(
  options: @TemporalDsl WorkflowImplementationOptions.Builder.() -> Unit
) {
  registerWorkflowImplementationTypes(T::class.java, options = options)
}

/**
 * Configures a factory to use when an instance of a workflow implementation is created.
 * !IMPORTANT to provide newly created instances, each time factory is applied.
 *
 * Unless mocking a workflow execution use [registerWorkflowImplementationTypes].
 *
 * @param T Workflow interface that this factory implements
 * @param factory factory that when called creates a new instance of the workflow implementation
 * object.
 */
inline fun <reified T : Any> Worker.addWorkflowImplementationFactory(
  options: WorkflowImplementationOptions,
  noinline factory: () -> T
) {
  addWorkflowImplementationFactory(options, T::class.java, factory)
}

/**
 * Configures a factory to use when an instance of a workflow implementation is created. The only
 * valid use for this method is unit testing, specifically to instantiate mocks that implement
 * child workflows. An example of mocking a child workflow:
 *
 * ```kotlin
 * worker.addWorkflowImplementationFactory<ChildWorkflow> {
 *   val child = mock<ChildWorkflow>()
 *   when(child.workflow(anyString(), anyString())).thenReturn("result1")
 *   child
 * }
 * ```
 *
 * Unless mocking a workflow execution use [registerWorkflowImplementationTypes].
 *
 * @param T Workflow interface that this factory implements
 * @param factory factory that when called creates a new instance of the workflow implementation
 * object.
 */
inline fun <reified T : Any> Worker.addWorkflowImplementationFactory(
  noinline factory: () -> T
) {
  addWorkflowImplementationFactory(T::class.java, factory)
}
