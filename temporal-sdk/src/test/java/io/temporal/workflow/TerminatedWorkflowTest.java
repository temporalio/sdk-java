package io.temporal.workflow;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.*;
import io.temporal.failure.TerminatedFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowWithQuery;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests verifying the correct behavior of the SDK if the workflow is in unsuccessful final states
 */
public class TerminatedWorkflowTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(NeverEndingWorkflowImpl.class).build();

  @Test
  public void testShouldReturnQueryResultAfterWorkflowTimeout() {
    // Queries only work if workflow task was started at least once before closing.
    // Without time skipping, we can't ensure this happens before the timeout.
    // To keep the test execution time low but to avoid making it time-sensitive, we are retrying
    // the workflow with exponentially growing timeouts until we get an execution that we can query.
    TestWorkflowWithQuery workflow;
    for (int i = 0; ; i++) {
      WorkflowOptions options =
          SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
              .setWorkflowRunTimeout(Duration.ofSeconds((int) Math.pow(2, i)))
              .build();

      workflow =
          testWorkflowRule
              .getWorkflowClient()
              .newWorkflowStub(TestWorkflowWithQuery.class, options);

      WorkflowFailedException e =
          Assert.assertThrows(
              "Workflow should throw because of timeout",
              WorkflowFailedException.class,
              workflow::execute);
      Assert.assertTrue(e.getCause() instanceof TimeoutFailure);

      WorkflowExecution execution = WorkflowStub.fromTyped(workflow).getExecution();
      if (testWorkflowRule
          .getWorkflowClient()
          .streamHistory(execution.getWorkflowId(), execution.getRunId())
          .anyMatch(evt -> evt.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED)) {
        break;
      }
    }

    Assert.assertEquals("started", workflow.query());
  }

  @Test
  public void getResultShouldThrowAfterTerminationOfWorkflow() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();
    WorkflowStub workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub("TestWorkflowWithQuery", options);

    workflow.start();
    workflow.terminate("testing");

    WorkflowFailedException e =
        Assert.assertThrows(
            "Workflow should throw WorkflowFailedException because the workflow was terminated",
            WorkflowFailedException.class,
            () -> workflow.getResult(String.class));
    assertTrue(e.getCause() instanceof TerminatedFailure);
  }

  public static class NeverEndingWorkflowImpl implements TestWorkflowWithQuery {
    private String state = "not started";

    @Override
    public String execute() {
      state = "started";
      Workflow.await(() -> false);
      return "should never happen";
    }

    @Override
    public String query() {
      return state;
    }
  }
}
