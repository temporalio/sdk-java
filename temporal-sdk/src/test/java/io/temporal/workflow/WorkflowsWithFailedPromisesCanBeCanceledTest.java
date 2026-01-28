package io.temporal.workflow;

import static org.junit.Assert.fail;

import com.google.common.util.concurrent.UncheckedExecutionException;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowsWithFailedPromisesCanBeCanceledTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestCancellationForWorkflowsWithFailedPromises.class)
          .build();

  @Test
  public void workflowsWithFailedPromisesCanBeCanceled() {
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

  public static class TestCancellationForWorkflowsWithFailedPromises implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      Async.function(
          () -> {
            throw new UncheckedExecutionException(new Exception("Oh noo!"));
          });
      Async.function(
          () -> {
            throw new UncheckedExecutionException(new Exception("Oh noo again!"));
          });
      Workflow.await(() -> false);
      fail("unreachable");
      return "done";
    }
  }
}
