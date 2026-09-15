package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.failure.TerminatedFailure;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowTerminationTest {

  private static final Signal childStarted = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class)
          .build();

  @Before
  public void setUp() throws Exception {
    childStarted.clearSignal();
  }

  @Test
  public void testChildWorkflowTermination() throws Exception {
    WorkflowStub client =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflowReturnString");
    WorkflowExecution execution = client.start();
    childStarted.waitForSignal();
    testWorkflowRule
        .getWorkflowClient()
        .newUntypedWorkflowStub("test-terminated-child-workflow-id")
        .terminate("Terminating child for test");
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowFailure);
      assertTrue(e.getCause().getCause() instanceof TerminatedFailure);
    }
    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED);
  }

  public static class TestParentWorkflowImpl implements TestWorkflowReturnString {

    @Override
    public String execute() {
      NoArgsWorkflow child =
          Workflow.newChildWorkflowStub(
              NoArgsWorkflow.class,
              ChildWorkflowOptions.newBuilder()
                  .setWorkflowId("test-terminated-child-workflow-id")
                  .build());
      child.execute();
      return "unreachable";
    }
  }

  public static class TestChildWorkflowImpl implements NoArgsWorkflow {

    @Override
    public void execute() {
      childStarted.signal();
      Workflow.await(() -> false);
    }
  }
}
