package io.temporal.aws.lambda;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.EncodedValues;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class DefaultLambdaWorkerRuntime implements LambdaWorkerRuntime {
  @Override
  public Invocation create(
      WorkflowServiceStubsOptions serviceStubsOptions,
      WorkflowClientOptions clientOptions,
      WorkerFactoryOptions workerFactoryOptions,
      String taskQueue,
      WorkerOptions workerOptions) {
    WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(serviceStubsOptions);
    WorkerFactory factory = null;
    try {
      WorkflowClient client = WorkflowClient.newInstance(stubs, clientOptions);
      factory = WorkerFactory.newInstance(client, workerFactoryOptions);
      Worker worker = factory.newWorker(taskQueue, workerOptions);
      return new DefaultInvocation(stubs, factory, worker);
    } catch (RuntimeException e) {
      if (factory != null) {
        try {
          factory.shutdownNow();
        } catch (RuntimeException shutdownException) {
          e.addSuppressed(shutdownException);
        }
      }
      try {
        stubs.shutdownNow();
      } catch (RuntimeException shutdownException) {
        e.addSuppressed(shutdownException);
      }
      throw e;
    }
  }

  private static final class DefaultInvocation implements Invocation {
    private final WorkflowServiceStubs stubs;
    private final WorkerFactory factory;
    private final WorkerRegistrar registrar;

    private DefaultInvocation(WorkflowServiceStubs stubs, WorkerFactory factory, Worker worker) {
      this.stubs = stubs;
      this.factory = factory;
      this.registrar = new DefaultWorkerRegistrar(worker);
    }

    @Override
    public WorkerRegistrar getWorkerRegistrar() {
      return registrar;
    }

    @Override
    public void start() {
      factory.start();
    }

    @Override
    public void shutdown() {
      factory.shutdown();
    }

    @Override
    public void shutdownNow() {
      factory.shutdownNow();
    }

    @Override
    public void awaitTermination(Duration timeout) {
      factory.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean isTerminated() {
      return factory.isTerminated();
    }

    @Override
    public void closeStubs(Duration timeout) {
      stubs.shutdown();
      if (!stubs.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        stubs.shutdownNow();
      }
    }
  }

  private static final class DefaultWorkerRegistrar implements WorkerRegistrar {
    private final Worker worker;

    private DefaultWorkerRegistrar(Worker worker) {
      this.worker = worker;
    }

    @Override
    public void registerWorkflowImplementationTypes(Class<?>... workflowImplementationClasses) {
      worker.registerWorkflowImplementationTypes(workflowImplementationClasses);
    }

    @Override
    public void registerWorkflowImplementationTypes(
        WorkflowImplementationOptions options, Class<?>... workflowImplementationClasses) {
      worker.registerWorkflowImplementationTypes(options, workflowImplementationClasses);
    }

    @Override
    public <R> void registerWorkflowImplementationFactory(
        Class<R> workflowInterface, Functions.Func<R> factory) {
      worker.registerWorkflowImplementationFactory(workflowInterface, factory);
    }

    @Override
    public <R> void registerWorkflowImplementationFactory(
        Class<R> workflowInterface,
        Functions.Func1<EncodedValues, R> factory,
        WorkflowImplementationOptions options) {
      worker.registerWorkflowImplementationFactory(workflowInterface, factory, options);
    }

    @Override
    public <R> void registerWorkflowImplementationFactory(
        Class<R> workflowInterface,
        Functions.Func<R> factory,
        WorkflowImplementationOptions options) {
      worker.registerWorkflowImplementationFactory(workflowInterface, factory, options);
    }

    @Override
    public void registerActivitiesImplementations(Object... activityImplementations) {
      worker.registerActivitiesImplementations(activityImplementations);
    }

    @Override
    public void registerNexusServiceImplementation(Object... nexusServiceImplementations) {
      worker.registerNexusServiceImplementation(nexusServiceImplementations);
    }
  }
}
