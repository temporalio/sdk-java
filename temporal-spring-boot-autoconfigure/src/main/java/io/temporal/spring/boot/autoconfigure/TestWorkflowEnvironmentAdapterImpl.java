package io.temporal.spring.boot.autoconfigure;

import io.temporal.client.WorkflowClient;
import io.temporal.spring.boot.autoconfigure.template.TestWorkflowEnvironmentAdapter;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactory;

class TestWorkflowEnvironmentAdapterImpl implements TestWorkflowEnvironmentAdapter {
  private final TestWorkflowEnvironment delegate;

  TestWorkflowEnvironmentAdapterImpl(TestWorkflowEnvironment testWorkflowEnvironment) {
    this.delegate = testWorkflowEnvironment;
  }

  @Override
  public WorkflowClient getWorkflowClient() {
    return delegate.getWorkflowClient();
  }

  @Override
  public WorkerFactory getWorkerFactory() {
    return delegate.getWorkerFactory();
  }
}
