/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.temporal.kotlin.workflow

import io.temporal.common.converter.EncodedValues

/**
 * Use DynamicWorkflow to implement any number of workflow types dynamically. When a workflow
 * implementation type that extends DynamicWorkflow is registered, it is used to implement any
 * workflow type that is not implicitly registered with the [io.temporal.worker.Worker]. Only
 * one type that implements DynamicWorkflow per worker is allowed.
 *
 *
 * The main use case for DynamicWorkflow is an implementation of custom Domain Specific Languages
 * (DSLs). A single implementation can implement a workflow type which definition is dynamically
 * loaded from some external source.
 *
 *
 * Use [Workflow.getInfo] to query information about the workflow type that should be
 * implemented dynamically.
 *
 *
 * Use [Workflow.registerListener] to register signal and query listeners. Consider
 * using [DynamicSignalHandler] and [DynamicQueryHandler] to implement handlers that can
 * support any signal or query type dynamically.
 *
 *
 * All the determinism rules still apply to workflows that implement this interface.
 *
 * @see io.temporal.activity.DynamicActivity
 */
interface KotlinDynamicWorkflow {
    suspend fun execute(args: EncodedValues): Any?
}