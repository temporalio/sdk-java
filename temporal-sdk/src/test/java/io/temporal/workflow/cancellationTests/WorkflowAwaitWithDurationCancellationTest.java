package io.temporal.workflow.cancellationTests;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowAwaitWithDurationCancellationTest {
  private static final Signal workflowStarted = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(AwaitingWorkflow.class).build();

  @Test
  public void awaitWithDurationCancellation() throws InterruptedException {
    workflowStarted.clearSignal();
    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflow1.class);
    WorkflowExecution execution = null;
    execution = WorkflowClient.start(workflow::execute, "input1");
    try {
      WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
      workflowStarted.waitForSignal();
      untyped.cancel();
      untyped.getResult(String.class);
      fail("unreacheable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
      History history =
          testWorkflowRule.getExecutionHistory(execution.getWorkflowId()).getHistory();

      HistoryEvent lastEvent = history.getEvents(history.getEventsCount() - 1);
      assertEquals(
          "WorkflowExecutionCancelled event is expected",
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED,
          lastEvent.getEventType());

      HistoryEvent oneBeforeLastEvent = history.getEvents(history.getEventsCount() - 2);
      assertEquals(
          "TimerCancelled event is expected because we should cancel the timer created for timed conditional wait",
          EventType.EVENT_TYPE_TIMER_CANCELED,
          oneBeforeLastEvent.getEventType());
    }
  }

  public static class AwaitingWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String input) {
      workflowStarted.signal();
      Workflow.await(Duration.ofHours(1), () -> false);
      return "success";
    }
  }
}
