package io.temporal.testing;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.worker.WorkerFactoryOptions;
import org.junit.Test;

public class TestWorkflowEnvironmentCreationTest {

  @Test
  public void testCreateWithValidatedDefaultOptions() {
    WorkflowClientOptions workflowClientOptions =
        WorkflowClientOptions.newBuilder().validateAndBuildWithDefaults();
    WorkerFactoryOptions workerFactoryOptions =
        WorkerFactoryOptions.newBuilder().validateAndBuildWithDefaults();
    TestWorkflowEnvironment.newInstance(
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(workflowClientOptions)
            .setWorkerFactoryOptions(workerFactoryOptions)
            .validateAndBuildWithDefaults());
  }
}
