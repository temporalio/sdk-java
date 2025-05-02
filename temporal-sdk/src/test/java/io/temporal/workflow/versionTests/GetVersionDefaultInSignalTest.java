package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionDefaultInSignalTest extends BaseVersionTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionDefaultInSignal() throws InterruptedException {
    TestWorkflows.TestSignaledWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestSignaledWorkflow.class);
    WorkflowClient.start(workflow::execute);

    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);
    SDKTestWorkflowRule.waitForOKQuery(workflowStub);

    workflow.signal(testWorkflowRule.getTaskQueue());
    workflow.signal(testWorkflowRule.getTaskQueue());
    testWorkflowRule.invalidateWorkflowCache();
    workflow.signal(testWorkflowRule.getTaskQueue());

    String result = workflowStub.getResult(String.class);
    assertEquals("1", result);
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflows.TestSignaledWorkflow {
    int signalCounter = 0;

    @Override
    public String execute() {
      int version =
          io.temporal.workflow.Workflow.getVersion(
              "testMarker", io.temporal.workflow.Workflow.DEFAULT_VERSION, 1);
      Workflow.await(() -> signalCounter >= 3);
      return String.valueOf(version);
    }

    @Override
    public void signal(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      int version =
          io.temporal.workflow.Workflow.getVersion(
              "testMarker", io.temporal.workflow.Workflow.DEFAULT_VERSION, 1);
      if (version == 1) {
        testActivities.activity1(1);
      } else {
        testActivities.activity();
      }
      signalCounter++;
    }
  }
}
