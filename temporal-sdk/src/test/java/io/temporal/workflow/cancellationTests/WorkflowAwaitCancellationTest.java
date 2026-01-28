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
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowAwaitCancellationTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(AwaitingWorkflow.class).build();

  @Test
  public void awaitCancellation() {
    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflow1.class);
    WorkflowExecution execution = null;
    execution = WorkflowClient.start(workflow::execute, "input1");
    try {
      WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
      untyped.cancel("test reason");
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
      assertEquals(
          "WorkflowExecutionCancelled event should have the correct cause",
          "test reason",
          WorkflowExecutionUtils.getEventOfType(
                  history, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED)
              .getWorkflowExecutionCancelRequestedEventAttributes()
              .getCause());
    }
  }

  public static class AwaitingWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String input) {
      Workflow.await(() -> false);
      return "success";
    }
  }
}
