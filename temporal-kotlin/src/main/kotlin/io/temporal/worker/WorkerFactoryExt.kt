
package io.temporal.worker

import io.temporal.client.WorkflowClient
import io.temporal.kotlin.TemporalDsl

/**
 * @see WorkerFactory
 */
fun WorkerFactory(
  workflowClient: WorkflowClient
): WorkerFactory {
  return WorkerFactory.newInstance(workflowClient)
}

/**
 * @see WorkerFactory
 */
fun WorkerFactory(
  workflowClient: WorkflowClient,
  options: @TemporalDsl WorkerFactoryOptions.Builder.() -> Unit
): WorkerFactory {
  return WorkerFactory.newInstance(workflowClient, WorkerFactoryOptions(options))
}

/**
 * Creates worker that connects to an instance of the Temporal Service.
 *
 * @see WorkerFactory.newWorker
 */
inline fun WorkerFactory.newWorker(
  taskQueue: String,
  options: @TemporalDsl WorkerOptions.Builder.() -> Unit
): Worker {
  return newWorker(taskQueue, WorkerOptions(options))
}
