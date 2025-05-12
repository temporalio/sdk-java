package io.temporal.workflow.cancellationTests;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

public class ExternalWorkflowCancelTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(CancellableWorkflowImpl.class, CancelExternalWorkflowImpl.class)
          .build();

  @Test
  public void cancelExternalWorkflow() {
    TestWorkflows.TestWorkflow1 cancellable =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    WorkflowClient.start(cancellable::execute, "ignored");
    WorkflowExecution execution = WorkflowStub.fromTyped(cancellable).getExecution();

    TestWorkflows.TestWorkflowStringArg canceler =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflowStringArg.class);
    canceler.execute(execution.getWorkflowId());

    WorkflowStub cancellableStub = WorkflowStub.fromTyped(cancellable);
    try {
      cancellableStub.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  public static class CancellableWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      Workflow.await(() -> false);
      return "done";
    }
  }

  public static class CancelExternalWorkflowImpl implements TestWorkflows.TestWorkflowStringArg {
    @Override
    public void execute(String targetWorkflowId) {
      TestWorkflows.TestWorkflow1 target =
          Workflow.newExternalWorkflowStub(TestWorkflows.TestWorkflow1.class, targetWorkflowId);
      ExternalWorkflowStub.fromTyped(target).cancel();
    }
  }
}
