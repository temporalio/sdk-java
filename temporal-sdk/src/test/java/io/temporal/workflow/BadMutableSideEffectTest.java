package io.temporal.workflow;

import static org.junit.Assert.assertThrows;

import io.temporal.client.WorkflowFailedException;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

public class BadMutableSideEffectTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(Error.class)
                  .build(),
              TestSideEffectWorkflowImpl.class)
          .build();

  @Test
  public void testBadMutableSideEffect() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    for (String testCase : TestWorkflows.illegalCallCases) {
      assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(testCase));
    }
  }

  public static class TestSideEffectWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String testCase) {
      Workflow.mutableSideEffect(
          "id",
          int.class,
          (Integer i, Integer j) -> {
            return i.equals(j);
          },
          () -> {
            TestWorkflows.illegalCalls(testCase);
            return 0;
          });
      return "";
    }
  }
}
