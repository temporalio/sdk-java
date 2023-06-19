package io.temporal.worker

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.internal.async.KotlinWorkflowImplementationFactory
import io.temporal.internal.replay.ReplayWorkflowFactory
import io.temporal.internal.worker.WorkflowExecutorCache

class KotlinWorkerFactory(workflowClient: WorkflowClient, factoryOptions: KotlinWorkerFactoryOptions?) :
  BaseWorkerFactory(workflowClient, toFactoryOptions(factoryOptions)) {

  override fun newReplayWorkflowFactory(
    workerOptions: WorkerOptions,
    clientOptions: WorkflowClientOptions,
    cache: WorkflowExecutorCache,
  ): ReplayWorkflowFactory {
    return KotlinWorkflowImplementationFactory(clientOptions, workerOptions, cache);
  }
}

// TODO(maxim): This is temporary hack until WorkerFactoryOptions are removed from base.
fun toFactoryOptions(factoryOptions: KotlinWorkerFactoryOptions?): WorkerFactoryOptions? {
  val o = KotlinWorkerFactoryOptions.newBuilder(factoryOptions).validateAndBuildWithDefaults()

  return WorkerFactoryOptions.newBuilder()
    .setEnableLoggingInReplay(o.isEnableLoggingInReplay)
    .setWorkflowCacheSize(o.workflowCacheSize)
    .build();
}
