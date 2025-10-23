package io.temporal.client.functional;

import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowStubFirstExecutionRunIdTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseExternalService(true)
          .setWorkflowTypes(AwaitingWorkflow.class)
          .build();

  @Test
  public void terminateFollowFirstRunId() throws InterruptedException {
    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflow1.class);
    WorkflowClient.start(workflow::execute, "input1");
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    // TODO wait for the continue as new to be visible
    Thread.sleep(1000);
    Assert.assertThrows(
        "If the workflow continued as new, terminating by execution without firstExecutionRunId should fail",
        WorkflowNotFoundException.class,
        () ->
            testWorkflowRule
                .getWorkflowClient()
                .newUntypedWorkflowStub(
                    Optional.empty(),
                    WorkflowTargetOptions.newBuilder()
                        .setWorkflowExecution(untyped.getExecution())
                        .build())
                .terminate("termination"));
    testWorkflowRule
        .getWorkflowClient()
        .newUntypedWorkflowStub(
            Optional.empty(),
            WorkflowTargetOptions.newBuilder()
                .setWorkflowExecution(untyped.getExecution())
                .setFirstExecutionRunId(untyped.getExecution().getRunId())
                .build())
        .terminate("termination");
    Assert.assertThrows(
        "Workflow should not be terminated",
        WorkflowFailedException.class,
        () -> untyped.getResult(String.class));
  }

  public static class AwaitingWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String arg) {
      if (!Workflow.getInfo().getContinuedExecutionRunId().isPresent()) {
        Workflow.continueAsNew();
      }
      Workflow.await(() -> false);
      return "done";
    }
  }
}
