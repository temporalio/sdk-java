package io.temporal.workflow;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowCancellationScopePromiseTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestCancellationScopePromise.class).build();

  @Test
  public void testWorkflowCancellationScopePromise() {
    WorkflowStub client = testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow1");
    client.start(testWorkflowRule.getTaskQueue());
    client.cancel();
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      Assert.assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  public static class TestCancellationScopePromise implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      Promise<String> cancellationRequest = CancellationScope.current().getCancellationRequest();
      cancellationRequest.get();
      return "done";
    }
  }
}
