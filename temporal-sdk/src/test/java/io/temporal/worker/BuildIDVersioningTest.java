package io.temporal.worker;

import io.temporal.client.BuildIDOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BuildIDVersioningTest {
  private static final Logger log = LoggerFactory.getLogger(BuildIDVersioningTest.class);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder().setBuildID("1.0").setUseBuildIDForVersioning(true).build())
          //          .setWorkflowTypes(BuildIDVersioningTest.TestUpdateWorkflowImpl.class)
          //          .setActivityImplementations(new UpdateTest.ActivityImpl())
          // TODO: remove when test server implements
          .setUseExternalService(true)
          .setNamespace("default")
          .build();

  @Test
  public void testBuildIDVersioningDataSetProperly() {
    String taskQueue = testWorkflowRule.getTaskQueue();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    // Add "1.0" to the queue
    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.newIDInNewDefaultSet("1.0"));
  }
}
