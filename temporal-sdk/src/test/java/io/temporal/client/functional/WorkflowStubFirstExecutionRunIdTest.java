package io.temporal.client.functional;

import io.temporal.api.enums.v1.EventType;
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
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(AwaitingWorkflow.class).build();

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

  @Test
  public void cancelFollowFirstRunId() throws InterruptedException {
    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflow1.class);
    WorkflowClient.start(workflow::execute, "input1");
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    // TODO wait for the continue as new to be visible
    Thread.sleep(1000);
    testWorkflowRule
        .getWorkflowClient()
        .newUntypedWorkflowStub(
            Optional.empty(),
            WorkflowTargetOptions.newBuilder().setWorkflowExecution(untyped.getExecution()).build())
        .cancel();
    testWorkflowRule.assertNoHistoryEvent(
        untyped.getExecution().getWorkflowId(),
        untyped.getExecution().getRunId(),
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED);

    testWorkflowRule
        .getWorkflowClient()
        .newUntypedWorkflowStub(
            Optional.empty(),
            WorkflowTargetOptions.newBuilder()
                .setWorkflowExecution(untyped.getExecution())
                .setFirstExecutionRunId(untyped.getExecution().getRunId())
                .build())
        .cancel();
    Assert.assertThrows(
        "Workflow should be cancelled",
        WorkflowFailedException.class,
        () -> untyped.getResult(String.class));
    testWorkflowRule.assertHistoryEvent(
        untyped.getExecution().getWorkflowId(),
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED);
  }

  @Test
  public void signalRespectRunId() throws InterruptedException {
    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflow1.class);
    WorkflowClient.start(workflow::execute, "input1");
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    // TODO wait for the continue as new to be visible
    Thread.sleep(1000);
    Assert.assertThrows(
        WorkflowNotFoundException.class,
        () ->
            testWorkflowRule
                .getWorkflowClient()
                .newUntypedWorkflowStub(
                    Optional.empty(),
                    WorkflowTargetOptions.newBuilder()
                        .setWorkflowExecution(untyped.getExecution())
                        .build())
                .signal("signal"));

    testWorkflowRule
        .getWorkflowClient()
        .newUntypedWorkflowStub(
            Optional.empty(),
            WorkflowTargetOptions.newBuilder()
                .setWorkflowId(untyped.getExecution().getWorkflowId())
                .build())
        .signal("signal");
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
