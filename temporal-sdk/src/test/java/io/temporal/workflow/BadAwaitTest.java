package io.temporal.workflow;

import static org.junit.Assert.assertThrows;

import io.temporal.client.WorkflowFailedException;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Rule;
import org.junit.Test;

public class BadAwaitTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(RuntimeException.class)
                  .build(),
              TestAwait.class)
          .build();

  @Test
  public void testBadAwait() {
    for (String testCase : TestWorkflows.illegalCallCases) {
      TestWorkflows.TestWorkflow1 workflowStub =
          testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
      assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(testCase));
    }
  }

  public static class TestAwait implements TestWorkflow1 {
    @Override
    public String execute(String testCase) {
      Workflow.await(
          () -> {
            TestWorkflows.illegalCalls(testCase);
            return true;
          });
      return "fail";
    }
  }
}
