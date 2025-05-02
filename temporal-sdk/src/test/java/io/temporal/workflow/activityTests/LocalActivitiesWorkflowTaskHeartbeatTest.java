package io.temporal.workflow.activityTests;

import com.google.common.base.Preconditions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivitiesWorkflowTaskHeartbeatTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  private static final int ACTIVITIES_COUNT = 3;
  private static final int ACTIVITY_SLEEP_SEC = 2;
  private static final int WORKFLOW_TASK_TIMEOUT_SEC = 4;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLocalActivitiesWorkflowTaskHeartbeatWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setTestTimeoutSeconds(ACTIVITIES_COUNT * ACTIVITY_SLEEP_SEC + 10)
          .build();

  /**
   * Emulates a workflow that triggers several relatively short consecutive local activities (that
   * sleep less than Workflow Task Timeout) that together process (or sleep) longer than Workflow
   * Task Timeout. Such consecutive local activities will be executed as a part of one big Workflow
   * Task.
   *
   * <p>This test makes sure than such a workflow and workflow task is not getting timed out by
   * performing heartbeats even while it takes longer than Workflow Task Timeout.
   *
   * @see LongLocalActivityWorkflowTaskHeartbeatTest
   */
  @Test
  public void testLocalActivitiesWorkflowTaskHeartbeat()
      throws ExecutionException, InterruptedException {
    Preconditions.checkState(
        ACTIVITY_SLEEP_SEC < WORKFLOW_TASK_TIMEOUT_SEC,
        "Sleep of each local activity is less than Workflow Task Timeout");
    Preconditions.checkState(
        ACTIVITIES_COUNT * ACTIVITY_SLEEP_SEC > WORKFLOW_TASK_TIMEOUT_SEC,
        "This test makes sense if we have several consecutive relatively short local activities "
            + "that sleep longer than Workflow Task Timeout");
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(WORKFLOW_TASK_TIMEOUT_SEC))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflow1 workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    Future<String> result =
        WorkflowClient.execute(workflowStub::execute, testWorkflowRule.getTaskQueue());
    Assert.assertEquals("sleepActivity0sleepActivity1sleepActivity2", result.get());
    Assert.assertEquals(
        activitiesImpl.toString(), ACTIVITIES_COUNT, activitiesImpl.invocations.size());
  }

  public static class TestLocalActivitiesWorkflowTaskHeartbeatWorkflowImpl
      implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class, SDKTestOptions.newLocalActivityOptions());
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < ACTIVITIES_COUNT; i++) {
        result.append(
            localActivities.sleepActivity(TimeUnit.SECONDS.toMillis(ACTIVITY_SLEEP_SEC), i));
      }
      return result.toString();
    }
  }
}
