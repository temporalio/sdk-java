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

import io.temporal.client.WorkflowClient
import io.temporal.common.converter.DataConverter
import io.temporal.kotlin.TemporalDsl

/**
 * Maintains worker creation and lifecycle.
 */
fun WorkerFactory(
  workflowClient: WorkflowClient
): WorkerFactory {
  return WorkerFactory.newInstance(workflowClient)
}

/**
 * Maintains worker creation and lifecycle.
 */
fun WorkerFactory(
  workflowClient: WorkflowClient,
  options: @TemporalDsl WorkerFactoryOptions.Builder.() -> Unit
): WorkerFactory {
  return WorkerFactory.newInstance(workflowClient, WorkerFactoryOptions(options))
}

/**
 * Creates worker that connects to an instance of the Temporal Service. It uses the namespace
 * configured at the Factory level. New workers cannot be created after the
 * [start][WorkerFactory.start] has been called.
 *
 * @param taskQueue task queue name worker uses to poll. It uses this name for both workflow and
 *     activity task queue polls.
 * @param options Options (like [DataConverter] override) for configuring worker.
 */
inline fun WorkerFactory.newWorker(
  taskQueue: String,
  options: @TemporalDsl WorkerOptions.Builder.() -> Unit,
): Worker {
  return newWorker(taskQueue, WorkerOptions(options))
}
