package io.temporal.workflow;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TerminatedFailure;
import io.temporal.internal.replay.ReplayWorkflowTaskHandler;
import io.temporal.internal.retryer.GrpcMessageTooLargeException;
import io.temporal.internal.worker.PollerOptions;
import io.temporal.testUtils.LoggerUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import java.time.Duration;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class GrpcMessageTooLargeTest {
  private static final String QUERY_ERROR_MESSAGE =
      "Failed to send query response: RESOURCE_EXHAUSTED: grpc: received message larger than max";
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
  public SDKTestWorkflowRule activityStartWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ActivityStartWorkflowImpl.class)
          .setActivityImplementations(new TestActivityImpl())
          .build();

  @Rule
  public SDKTestWorkflowRule failureWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(FailureWorkflowImpl.class).build();

  @Rule
  public SDKTestWorkflowRule querySuccessWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(QuerySuccessWorkflowImpl.class).build();

  @Rule
  public SDKTestWorkflowRule queryFailureWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(QueryFailureWorkflowImpl.class).build();

  @Test
  public void workflowStartTooLarge() {
    TestWorkflow workflow = createWorkflowStub(TestWorkflow.class, activityStartWorkflowRule);
    WorkflowServiceException e =
        assertThrows(
            WorkflowServiceException.class,
            () -> WorkflowClient.start(workflow::execute, VERY_LARGE_DATA));
    assertTrue(e.getCause() instanceof GrpcMessageTooLargeException);
  }

  @Test
  public void activityStartTooLarge() {
    TestWorkflow workflow = createWorkflowStub(TestWorkflow.class, activityStartWorkflowRule);

    WorkflowFailedException e =
        assertThrows(WorkflowFailedException.class, () -> workflow.execute(""));
    assertTrue(e.getCause() instanceof TerminatedFailure);

    String workflowId = WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId();
    assertTrue(
        activityStartWorkflowRule
            .getHistoryEvents(workflowId, EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED)
            .isEmpty());
    List<HistoryEvent> events =
        activityStartWorkflowRule.getHistoryEvents(
            workflowId, EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
    assertEquals(1, events.size());
    assertEquals(
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_FORCE_CLOSE_COMMAND,
        events.get(0).getWorkflowTaskFailedEventAttributes().getCause());
  }

  @Test
  public void workflowFailureTooLarge() {
    // Avoding logging exception with very large data
    try (LoggerUtils.SilenceLoggers sl =
        LoggerUtils.silenceLoggers(ReplayWorkflowTaskHandler.class, PollerOptions.class)) {
      TestWorkflow workflow = createWorkflowStub(TestWorkflow.class, failureWorkflowRule);

      WorkflowFailedException e =
          assertThrows(WorkflowFailedException.class, () -> workflow.execute(""));

      assertTrue(e.getCause() instanceof TerminatedFailure);
      String workflowId = WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId();
      List<HistoryEvent> events =
          failureWorkflowRule.getHistoryEvents(
              workflowId, EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
      assertEquals(1, events.size());
      assertEquals(
          WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_FORCE_CLOSE_COMMAND,
          events.get(0).getWorkflowTaskFailedEventAttributes().getCause());
    }
  }

  @Test
  public void queryResultTooLarge() {
    TestWorkflowWithQuery workflow =
        createWorkflowStub(TestWorkflowWithQuery.class, querySuccessWorkflowRule);
    workflow.execute();

    WorkflowQueryException e = assertThrows(WorkflowQueryException.class, workflow::query);

    assertNotNull(e.getCause());
    // The exception will not contain the original failure object, so instead of type check we're
    // checking the message to ensure the correct error is being sent.
    assertTrue(e.getCause().getMessage().contains(QUERY_ERROR_MESSAGE));
  }

  @Test
  public void queryErrorTooLarge() {
    TestWorkflowWithQuery workflow =
        createWorkflowStub(TestWorkflowWithQuery.class, queryFailureWorkflowRule);
    workflow.execute();

    WorkflowQueryException e = assertThrows(WorkflowQueryException.class, workflow::query);

    assertNotNull(e.getCause());
    assertTrue(e.getCause().getMessage().contains(QUERY_ERROR_MESSAGE));
  }

  private static <T> T createWorkflowStub(Class<T> clazz, SDKTestWorkflowRule workflowRule) {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1))
            .setWorkflowTaskTimeout(Duration.ofMillis(250))
            .setTaskQueue(workflowRule.getTaskQueue())
            .build();
    return workflowRule.getWorkflowClient().newWorkflowStub(clazz, options);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void execute(String arg);
  }

  @WorkflowInterface
  public interface TestWorkflowWithQuery {
    @WorkflowMethod
    void execute();

    @QueryMethod
    String query();
  }

  public static class ActivityStartWorkflowImpl implements TestWorkflow {
    private final TestActivities.TestActivity1 activity =
        Workflow.newActivityStub(
            TestActivities.TestActivity1.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(1))
                .validateAndBuildWithDefaults());

    @Override
    public void execute(String arg) {
      activity.execute(VERY_LARGE_DATA);
    }
  }

  public static class FailureWorkflowImpl implements TestWorkflow {
    @Override
    public void execute(String arg) {
      throw new RuntimeException(VERY_LARGE_DATA);
    }
  }

  public static class QuerySuccessWorkflowImpl implements TestWorkflowWithQuery {
    @Override
    public void execute() {}

    @Override
    public String query() {
      return VERY_LARGE_DATA;
    }
  }

  public static class QueryFailureWorkflowImpl implements TestWorkflowWithQuery {
    @Override
    public void execute() {}

    @Override
    public String query() {
      throw new RuntimeException(VERY_LARGE_DATA);
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
