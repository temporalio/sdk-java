package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionMultipleCallsTest extends BaseVersionTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              getDefaultWorkflowImplementationOptions(), TestGetVersionWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  public GetVersionMultipleCallsTest(boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Test
  public void testGetVersionMultipleCalls() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertEquals("activity1", result);
  }

  @Test
  public void testGetVersionMultipleCallsDoesNotYieldBeforeSleep() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowExecution execution =
        WorkflowClient.start(workflow::execute, testWorkflowRule.getTaskQueue());
    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);

    String result = workflowStub.getResult(String.class);
    assertEquals("activity1", result);

    List<HistoryEvent> historyEvents =
        testWorkflowRule
            .getExecutionHistory(execution.getWorkflowId())
            .getHistory()
            .getEventsList();
    int completedWorkflowTasksBeforeTimer = 0;
    boolean timerStarted = false;
    boolean workflowTaskFailedBeforeTimer = false;
    int workflowSignalsBeforeTimer = 0;
    for (HistoryEvent event : historyEvents) {
      if (event.getEventType() == EventType.EVENT_TYPE_TIMER_STARTED) {
        timerStarted = true;
        break;
      }
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
        completedWorkflowTasksBeforeTimer++;
      }
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED) {
        workflowTaskFailedBeforeTimer = true;
      }
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED) {
        workflowSignalsBeforeTimer++;
      }
    }

    assertTrue("Expected Workflow.sleep to start a timer", timerStarted);
    assertFalse(
        "Duplicate getVersion calls should not fail the workflow task before the timer starts",
        workflowTaskFailedBeforeTimer);
    assertEquals(
        "Duplicate getVersion calls should not need a signal before Workflow.sleep starts a timer",
        0,
        workflowSignalsBeforeTimer);
    assertEquals(
        "Duplicate getVersion calls before Workflow.sleep should stay in the same workflow task",
        1,
        completedWorkflowTasksBeforeTimer);
  }

  @Test
  public void testGetVersionMultipleCallsReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testGetVersionMultipleCallsHistory.json",
        GetVersionMultipleCallsTest.TestGetVersionWorkflowImpl.class);
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
      int version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
      assertEquals(1, version);

      // Try again in the same WFT
      version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
      assertEquals(1, version);

      // Create a new WFT by sleeping
      Workflow.sleep(1000);
      version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
      assertEquals(1, version);
      String result = "activity" + testActivities.activity1(1);

      version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
      assertEquals(1, version);

      return result;
    }
  }
}
