package io.temporal.aws.lambda;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import java.time.Duration;

interface LambdaWorkerRuntime {
  Invocation create(
      WorkflowServiceStubsOptions serviceStubsOptions,
      WorkflowClientOptions clientOptions,
      WorkerFactoryOptions workerFactoryOptions,
      String taskQueue,
      WorkerOptions workerOptions);

  interface Invocation {
    WorkerRegistrar getWorkerRegistrar();

    void start();

    void shutdown();

    void shutdownNow();

    void awaitTermination(Duration timeout);

    boolean isTerminated();

    void closeStubs(Duration timeout);
  }
}
