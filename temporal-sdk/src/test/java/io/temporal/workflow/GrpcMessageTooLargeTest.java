package io.temporal.workflow;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.internal.retryer.GrpcMessageTooLargeException;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class GrpcMessageTooLargeTest {
  private static final String VERY_LARGE_DATA;

  static {
    String argPiece = "Very Large Data ";
    int argRepeats = 500_000; // circa 8MB, double the 4MB limit
    StringBuilder argBuilder = new StringBuilder(argPiece.length() * argRepeats);
    for (int i = 0; i < argRepeats; i++) {
      argBuilder.append(argPiece);
    }
    VERY_LARGE_DATA = argBuilder.toString();
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new TestActivityImpl())
          .build();

  @Test
  public void workflowStartTooLarge() {
    TestWorkflows.TestWorkflowStringArg workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowStringArg.class);
    WorkflowServiceException e =
        assertThrows(
            WorkflowServiceException.class,
            () -> WorkflowClient.start(workflow::execute, VERY_LARGE_DATA));
    assertTrue(e.getCause() instanceof GrpcMessageTooLargeException);
  }

  @Test
  public void activityStartTooLarge() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflows.TestWorkflowStringArg workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestWorkflowStringArg.class, options);

    WorkflowFailedException e =
        assertThrows(WorkflowFailedException.class, () -> workflow.execute(""));
    assertTrue(e.getCause() instanceof TimeoutFailure);

    List<HistoryEvent> events =
        testWorkflowRule.getHistoryEvents(
            e.getExecution().getWorkflowId(), EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
    assertFalse(events.isEmpty());
    for (HistoryEvent event : events) {
      assertEquals(
          WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_GRPC_MESSAGE_TOO_LARGE,
          event.getWorkflowTaskFailedEventAttributes().getCause());
    }
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflowStringArg {
    @Override
    public void execute(String arg) {
      Workflow.newActivityStub(
              TestActivities.TestActivity1.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(1))
                  .validateAndBuildWithDefaults())
          .execute(VERY_LARGE_DATA);
    }
  }

  public static class TestActivityImpl implements TestActivities.TestActivity1 {
    @Override
    public String execute(String arg) {
      throw ApplicationFailure.newBuilder()
          .setMessage("This activity should not start executing")
          .setType("TestFailure")
          .setNonRetryable(true)
          .build();
    }
  }
}
