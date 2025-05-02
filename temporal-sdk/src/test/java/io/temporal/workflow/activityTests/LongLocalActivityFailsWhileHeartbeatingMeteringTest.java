package io.temporal.workflow.activityTests;

import static org.junit.Assume.assumeFalse;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LongLocalActivityFailsWhileHeartbeatingMeteringTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  private static final int WORKFLOW_TASK_TIMEOUT_SEC = 2;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLongLocalActivityWorkflowTaskHeartbeatFailureWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setTestTimeoutSeconds(4 * WORKFLOW_TASK_TIMEOUT_SEC + 10)
          .build();

  /**
   * Test that local activity that failed to heartbeat and executed longer than Workflow Task
   * Timeout will be repeated during replay
   */
  @Test
  public void testLongLocalActivityFailsWhileHeartbeatingMetering() {
    // Needs server release which propagates metering metadata to event
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(WORKFLOW_TASK_TIMEOUT_SEC))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflows.TestWorkflowReturnString workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class, options);
    workflowStub.execute();
    List<HistoryEvent> taskCompleteEvents =
        testWorkflowRule.getHistoryEvents(
            WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId(),
            EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED);
    List<Integer> nonFirstLocalActivityExecutionAttempts =
        taskCompleteEvents.stream()
            .map(
                e ->
                    e.getWorkflowTaskCompletedEventAttributes()
                        .getMeteringMetadata()
                        .getNonfirstLocalActivityExecutionAttempts())
            .collect(Collectors.toList());
    // First task should have 0 non-first local activity execution attempts
    Assert.assertEquals(0, nonFirstLocalActivityExecutionAttempts.get(0).intValue());
    for (int i = 1; i < nonFirstLocalActivityExecutionAttempts.size(); i++) {
      // All other tasks should have positive non-first local activity execution attempts
      Assert.assertTrue(nonFirstLocalActivityExecutionAttempts.get(i) > 0);
    }
  }

  public static class TestLongLocalActivityWorkflowTaskHeartbeatFailureWorkflowImpl
      implements TestWorkflows.TestWorkflowReturnString {

    @Override
    public String execute() {
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(100))
              .setStartToCloseTimeout(Duration.ofSeconds(1))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setMaximumInterval(Duration.ofMillis(510))
                      .setInitialInterval(Duration.ofMillis(510))
                      .setBackoffCoefficient(1)
                      .setMaximumAttempts(6)
                      .build())
              .build();
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(VariousTestActivities.class, options);
      try {
        localActivities.throwIO();
      } catch (ActivityFailure e) {
        // We expect the activity to fail
      }
      try {
        localActivities.throwIO();
      } catch (ActivityFailure e) {
        // We expect the activity to fail
      }
      return "yay";
    }
  }
}
