package io.temporal.client.functional;

import static org.junit.Assert.*;

import io.temporal.client.BuildIDOperation;
import io.temporal.client.WorkerBuildIDVersionSets;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;

public class BuildIDVersionSetsTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          // TODO: remove when test server implements
          .setUseExternalService(true)
          .setNamespace("default")
          .build();

  @Test
  public void testManipulateGraph() {
    String taskQueue = testWorkflowRule.getTaskQueue();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.newIDInNewDefaultSet("1.0"));
    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.newIDInNewDefaultSet("2.0"));
    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.newCompatibleVersion("1.1", "1.0"));

    WorkerBuildIDVersionSets sets = workflowClient.getWorkerBuildIDCompatability(taskQueue);
    assertEquals("2.0", sets.defaultBuildID());
    assertEquals(2, sets.allSets().size());
    assertEquals(Arrays.asList("1.0", "1.1"), sets.allSets().get(0).getBuildIDs());

    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.promoteSetByBuildID("1.0"));
    sets = workflowClient.getWorkerBuildIDCompatability(taskQueue);
    assertEquals("1.1", sets.defaultBuildID());

    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.promoteBuildIDWithinSet("1.0"));
    sets = workflowClient.getWorkerBuildIDCompatability(taskQueue);
    assertEquals("1.0", sets.defaultBuildID());

    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.mergeSets("2.0", "1.0"));
    sets = workflowClient.getWorkerBuildIDCompatability(taskQueue);
    assertEquals("2.0", sets.defaultBuildID());
  }
}
