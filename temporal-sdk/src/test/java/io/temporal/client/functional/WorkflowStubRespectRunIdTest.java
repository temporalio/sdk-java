package io.temporal.client.functional;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowStubRespectRunIdTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(AwaitingWorkflow.class).build();

  @Test
  public void terminateFollowFirstRunId() throws InterruptedException {
    TestWorkflowWithQuery workflow = testWorkflowRule.newWorkflowStub(TestWorkflowWithQuery.class);
    WorkflowClient.start(workflow::execute, "input1");
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    waitForContinueAsNew(untyped.getExecution());
    Assert.assertThrows(
        "If the workflow continued as new, terminating by execution without firstExecutionRunId should fail",
        WorkflowNotFoundException.class,
        () ->
            testWorkflowRule
                .getWorkflowClient()
                .newUntypedWorkflowStub(
                    WorkflowTargetOptions.newBuilder()
                        .setWorkflowExecution(untyped.getExecution())
                        .build())
                .terminate("termination"));
    testWorkflowRule
        .getWorkflowClient()
        .newUntypedWorkflowStub(
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
    TestWorkflowWithQuery workflow = testWorkflowRule.newWorkflowStub(TestWorkflowWithQuery.class);
    WorkflowClient.start(workflow::execute, "input1");
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    waitForContinueAsNew(untyped.getExecution());
    testWorkflowRule
        .getWorkflowClient()
        .newUntypedWorkflowStub(
            WorkflowTargetOptions.newBuilder().setWorkflowExecution(untyped.getExecution()).build())
        .cancel();
    testWorkflowRule.assertNoHistoryEvent(
        untyped.getExecution().getWorkflowId(),
        untyped.getExecution().getRunId(),
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED);

    testWorkflowRule
        .getWorkflowClient()
        .newUntypedWorkflowStub(
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
    TestWorkflowWithQuery workflow = testWorkflowRule.newWorkflowStub(TestWorkflowWithQuery.class);
    WorkflowClient.start(workflow::execute, "input1");
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    waitForContinueAsNew(untyped.getExecution());
    Assert.assertThrows(
        WorkflowNotFoundException.class,
        () ->
            testWorkflowRule
                .getWorkflowClient()
                .newUntypedWorkflowStub(
                    WorkflowTargetOptions.newBuilder()
                        .setWorkflowExecution(untyped.getExecution())
                        .build())
                .signal("signal"));

    testWorkflowRule
        .getWorkflowClient()
        .newUntypedWorkflowStub(
            WorkflowTargetOptions.newBuilder()
                .setWorkflowId(untyped.getExecution().getWorkflowId())
                .build())
        .signal("signal");
  }

  private void waitForContinueAsNew(WorkflowExecution execution) throws InterruptedException {
    final int maxAttempts = 5; // 100 * 100ms = 10s
    final long sleepMs = 1000L;
    int attempts = 0;

    WorkflowStub latestStub =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(
                WorkflowTargetOptions.newBuilder()
                    .setWorkflowId(execution.getWorkflowId())
                    .build());

    while (attempts++ < maxAttempts) {
      try {
        String currentRunId = latestStub.describe().getExecution().getRunId();
        if (!execution.getRunId().equals(currentRunId)) {
          return;
        }
      } catch (Exception e) {
        // Ignore and retry until timeout (query may fail while continue-as-new is in progress)
      }
      Thread.sleep(sleepMs);
    }

    throw new AssertionError(
        "ContinueAsNew event was not observed for workflowId: " + execution.getWorkflowId());
  }

  @Test
  public void queryRespectRunId() throws InterruptedException {
    TestWorkflowWithQuery workflow = testWorkflowRule.newWorkflowStub(TestWorkflowWithQuery.class);
    WorkflowClient.start(workflow::execute, "input1");
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    waitForContinueAsNew(untyped.getExecution());
    String actualRunId =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(
                WorkflowTargetOptions.newBuilder()
                    .setWorkflowExecution(untyped.getExecution())
                    .build())
            .query("getRunId", String.class);
    Assert.assertEquals(untyped.getExecution().getRunId(), actualRunId);

    actualRunId =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(
                WorkflowTargetOptions.newBuilder()
                    .setWorkflowId(untyped.getExecution().getWorkflowId())
                    .build())
            .query("getRunId", String.class);
    Assert.assertNotEquals(untyped.getExecution().getRunId(), actualRunId);
  }

  @WorkflowInterface
  public interface TestWorkflowWithQuery {
    @WorkflowMethod()
    String execute(String arg);

    @QueryMethod()
    String getRunId();
  }

  public static class AwaitingWorkflow implements TestWorkflowWithQuery {

    @Override
    public String execute(String arg) {
      if (!Workflow.getInfo().getContinuedExecutionRunId().isPresent()) {
        Workflow.continueAsNew();
      }
      Workflow.await(() -> false);
      return "done";
    }

    @Override
    public String getRunId() {
      return Workflow.getInfo().getRunId();
    }
  }
}
