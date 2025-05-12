package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowStartInCancelledScopeTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class)
          .build();

  @Test
  public void testStartChildInCancelledScope() {
    TestParentWorkflow workflow = testWorkflowRule.newWorkflowStub(TestParentWorkflow.class);
    try {
      workflow.execute();
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
      CanceledFailure failure = (CanceledFailure) e.getCause();
      assertTrue(failure.getOriginalMessage().contains("execute called from a cancelled scope"));
      
    }
  }

  @WorkflowInterface
  public interface TestParentWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class TestParentWorkflowImpl implements TestParentWorkflow {
    @Override
    public void execute() {
      TestWorkflows.NoArgsWorkflow child =
          Workflow.newChildWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
      CancellationScope scope = Workflow.newCancellationScope(child::execute);
      scope.cancel();
      scope.run();
    }
  }

  public static class TestChildWorkflowImpl implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {}
  }
}
