package io.temporal.workflow;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestNoArgsWorkflowFuncParent;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GetAttemptFromWorkflowInfoTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestNoArgsWorkflowFuncParent.class, TestAttemptReturningWorkflowFunc.class)
          .build();

  @Test
  public void testGetAttemptFromWorkflowInfo() {
    String workflowId = "testGetAttemptWorkflow";
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestGetAttemptWorkflowsFunc workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestGetAttemptWorkflowsFunc.class, workflowOptions);
    int attempt = workflow.func();
    Assert.assertEquals(1, attempt);
  }

  @WorkflowInterface
  public interface TestGetAttemptWorkflowsFunc {

    @WorkflowMethod
    int func();
  }

  public static class TestAttemptReturningWorkflowFunc implements TestGetAttemptWorkflowsFunc {
    @Override
    public int func() {
      WorkflowInfo wi = Workflow.getInfo();
      return wi.getAttempt();
    }
  }
}
