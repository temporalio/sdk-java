package io.temporal.workflow.updateTest;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.common.converter.EncodedValues;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.DynamicUpdateHandler;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class DynamicUpdateTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestDynamicUpdateWorkflowImpl.class)
          .build();

  @Test
  public void dynamicUpdate() throws ExecutionException, InterruptedException {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestWorkflows.TestWorkflow1 workflow =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, "input");
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);

    assertEquals(
        "update:update input",
        stub.startUpdate("update", WorkflowUpdateStage.COMPLETED, String.class, "update input")
            .getResultAsync()
            .get());

    Assert.assertThrows(
        WorkflowUpdateException.class,
        () ->
            stub.startUpdate("reject", WorkflowUpdateStage.COMPLETED, String.class, "update input")
                .getResult());

    stub.startUpdate("complete", WorkflowUpdateStage.COMPLETED, Void.class).getResultAsync().get();

    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(
                WorkflowTargetOptions.newBuilder().setWorkflowExecution(execution).build())
            .getResult(String.class);
    assertEquals(" update complete", result);
  }

  public static class TestDynamicUpdateWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    CompletablePromise<Void> promise = Workflow.newPromise();
    List<String> updates = new ArrayList<>();

    public TestDynamicUpdateWorkflowImpl() {
      Workflow.registerListener(
          new DynamicUpdateHandler() {
            @Override
            public void handleValidate(String updateName, EncodedValues args) {
              if (updateName.equals("reject")) {
                throw new IllegalArgumentException("simulated failure");
              }
            }

            @Override
            public Object handleExecute(String updateName, EncodedValues args) {
              updates.add(updateName);
              if (updateName.equals("complete")) {
                promise.complete(null);
                return null;
              }
              return "update:" + args.get(0, String.class);
            }
          });
    }

    @Override
    public String execute(String input) {
      promise.get();
      return updates.stream().reduce("", (a, b) -> a + " " + b);
    }
  }
}
