package io.temporal.spring.boot.autoconfigure.template;

import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactory;

/**
 * Creates a level of indirection over the {@link TestWorkflowEnvironment} that allows the
 * AutoConfiguration and Starter to work with temporal-testing dependency in classpath. Otherwise,
 * users face {@link java.lang.NoClassDefFoundError} for {@link TestWorkflowEnvironment}.
 */
public interface TestWorkflowEnvironmentAdapter {

  WorkflowClient getWorkflowClient();

  WorkerFactory getWorkerFactory();
}
