
package io.temporal.client

import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.kotlin.TemporalDsl
import io.temporal.serviceclient.WorkflowServiceStubs
import java.util.Optional

/**
 * Creates client that connects to an instance of the Temporal Service.
 *
 * @see WorkflowClient.newInstance
 */
fun WorkflowClient(
  service: WorkflowServiceStubs
): WorkflowClient {
  return WorkflowClient.newInstance(service)
}

/**
 * Creates client that connects to an instance of the Temporal Service.
 *
 * @see WorkflowClient.newInstance
 */
inline fun WorkflowClient(
  service: WorkflowServiceStubs,
  options: @TemporalDsl WorkflowClientOptions.Builder.() -> Unit
): WorkflowClient {
  return WorkflowClient.newInstance(service, WorkflowClientOptions(options))
}

/**
 * Creates workflow client stub that can be used to start a single workflow execution.
 *
 * @param T interface that given workflow implements
 * @see WorkflowClient.newWorkflowStub
 */
inline fun <reified T : Any> WorkflowClient.newWorkflowStub(
  options: WorkflowOptions
): T {
  return newWorkflowStub(T::class.java, options)
}

/**
 * Creates workflow client stub that can be used to start a single workflow execution.
 *
 * @param T interface that given workflow implements
 * @see WorkflowClient.newWorkflowStub
 */
inline fun <reified T : Any> WorkflowClient.newWorkflowStub(
  options: @TemporalDsl WorkflowOptions.Builder.() -> Unit
): T {
  return newWorkflowStub(T::class.java, WorkflowOptions(options))
}

/**
 * Creates workflow client stub for a known execution.
 *
 * @param T interface that given workflow implements.
 * @see WorkflowClient.newWorkflowStub
 */
inline fun <reified T : Any> WorkflowClient.newWorkflowStub(
  workflowId: String
): T {
  return newWorkflowStub(T::class.java, workflowId)
}

/**
 * Creates workflow client stub for a known execution.
 *
 * @param T interface that given workflow implements.
 * @see WorkflowClient.newWorkflowStub
 */
inline fun <reified T : Any> WorkflowClient.newWorkflowStub(
  workflowId: String,
  runId: String?
): T {
  return newWorkflowStub(T::class.java, workflowId, Optional.ofNullable(runId))
}

/**
 * Creates workflow untyped client stub that can be used to start a single workflow execution.
 *
 * @see WorkflowClient.newWorkflowStub
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
 * @param signals Must include exactly two `add` blocks with workflow operations.
 *        One annotated with `@WorkflowMethod` and another with `@SignalMethod`.
 * @see WorkflowClient.newSignalWithStartRequest
 * @see WorkflowClient.signalWithStart
 */
inline fun WorkflowClient.signalWithStart(
  signals: @TemporalDsl BatchRequest.() -> Unit
): WorkflowExecution {
  return signalWithStart(newSignalWithStartRequest().apply(signals))
}
