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

package io.temporal.client

import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.common.converter.DataConverter
import io.temporal.kotlin.TemporalDsl
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.workflow.WorkflowMethod
import java.util.Optional

/**
 * Creates client that connects to an instance of the Temporal Service.
 *
 * @param service client to the Temporal Service endpoint.
 */
fun WorkflowClient(
  service: WorkflowServiceStubs
): WorkflowClient {
  return WorkflowClient.newInstance(service)
}

/**
 * Creates client that connects to an instance of the Temporal Service.
 *
 * @param service client to the Temporal Service endpoint.
 * @param options Options (like [DataConverter] override) for configuring client.
 */
inline fun WorkflowClient(
  service: WorkflowServiceStubs,
  options: @TemporalDsl WorkflowClientOptions.Builder.() -> Unit
): WorkflowClient {
  return WorkflowClient.newInstance(service, WorkflowClientOptions(options))
}

/**
 * Creates workflow client stub that can be used to start a single workflow execution. The first
 * call must be to a method annotated with [WorkflowMethod]. After workflow is started it can be
 * also used to send signals or queries to it. IMPORTANT! Stub is per workflow instance. So new
 * stub should be created for each new one.
 *
 * @param T interface that given workflow implements
 * @param options options used to start a workflow through returned stub
 * @return Stub that implements workflow interface [T] and can be used to start workflow and later
 * to signal or query it.
 */
inline fun <reified T : Any> WorkflowClient.newWorkflowStub(
  options: WorkflowOptions
): T {
  return newWorkflowStub(T::class.java, options)
}

/**
 * Creates workflow client stub that can be used to start a single workflow execution. The first
 * call must be to a method annotated with [WorkflowMethod]. After workflow is started it can be
 * also used to send signals or queries to it. IMPORTANT! Stub is per workflow instance. So new
 * stub should be created for each new one.
 *
 * @param T interface that given workflow implements
 * @param options options used to start a workflow through returned stub
 * @return Stub that implements workflow interface [T] and can be used to start workflow and later
 * to signal or query it.
 */
inline fun <reified T : Any> WorkflowClient.newWorkflowStub(
  options: @TemporalDsl WorkflowOptions.Builder.() -> Unit
): T {
  return newWorkflowStub(T::class.java, WorkflowOptions(options))
}

/**
 * Creates workflow client stub for a known execution. Use it to send signals or queries to a
 * running workflow. Do not call methods annotated with [WorkflowMethod].
 *
 * @param T interface that given workflow implements.
 * @param workflowId Workflow id.
 * @return Stub that implements workflow interface [T] and can be used to signal or query it.
 */
inline fun <reified T : Any> WorkflowClient.newWorkflowStub(
  workflowId: String
): T {
  return newWorkflowStub(T::class.java, workflowId)
}

/**
 * Creates workflow client stub for a known execution. Use it to send signals or queries to a
 * running workflow. Do not call methods annotated with [WorkflowMethod].
 *
 * @param T interface that given workflow implements.
 * @param workflowId Workflow id.
 * @param runId Run id of the workflow execution.
 * @return Stub that implements workflow interface [T] and can be used to signal or query it.
 */
inline fun <reified T : Any> WorkflowClient.newWorkflowStub(
  workflowId: String,
  runId: String?
): T {
  return newWorkflowStub(T::class.java, workflowId, Optional.ofNullable(runId))
}

/**
 * Creates workflow untyped client stub that can be used to start a single workflow execution.
 * After workflow is started it can be also used to send signals or queries to it. IMPORTANT! Stub
 * is per workflow instance. So new stub should be created for each new one.
 *
 * @param workflowType name of the workflow type
 * @param options options used to start a workflow through returned stub
 * @return Stub that can be used to start workflow and later to signal or query it.
 */
inline fun WorkflowClient.newUntypedWorkflowStub(
  workflowType: String,
  options: @TemporalDsl WorkflowOptions.Builder.() -> Unit
): WorkflowStub {
  return newUntypedWorkflowStub(workflowType, WorkflowOptions(options))
}

/**
 * Invoke `SignalWithStart` operation.
 *
 * ```kotlin
 * val execution = workflowClient.signalWithStart {
 *     add { workflow.startMethod(startParams) }
 *     add { workflow.signalMethod(signalParams) }
 * }
 * ```
 *
 * @param signals Must include exactly two workflow operations.
 *        One annotated with `@WorkflowMethod` and another with `@SignalMethod`.
 * @return workflowId and runId of the signaled or started workflow.
 */
inline fun WorkflowClient.signalWithStart(
  signals: @TemporalDsl BatchRequest.() -> Unit
): WorkflowExecution {
  return signalWithStart(newSignalWithStartRequest().apply(signals))
}
