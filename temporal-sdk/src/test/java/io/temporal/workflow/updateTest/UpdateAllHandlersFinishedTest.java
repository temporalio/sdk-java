package io.temporal.workflow.updateTest;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.Rule;
import org.junit.Test;

public class UpdateAllHandlersFinishedTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestUpdateWorkflowImpl.class).build();

  @Test
  public void isEveryHandlerFinished() throws ExecutionException, InterruptedException {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    WorkflowWithUpdate workflow = workflowClient.newWorkflowStub(WorkflowWithUpdate.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    WorkflowStub untypedStub = workflowClient.newUntypedWorkflowStub(execution.getWorkflowId());
    List<WorkflowUpdateHandle<String>> updateHandles = new ArrayList<>();
    // Send a bunch of update requests
    for (int i = 0; i < 5; i++) {
      updateHandles.add(
          untypedStub.startUpdate(
              "update", WorkflowUpdateStage.ACCEPTED, String.class, "update request " + i));
    }
    // Try to complete the workflow, expect workflow to wait for the update handlers to finish first
    workflow.tryComplete();
    assertEquals(
        " update request 0 update request 1 update request 2 update request 3 update request 4",
        workflow.execute());
    // Ensure that all update handlers actually finished
    for (int i = 0; i < 5; i++) {
      assertEquals("update request " + i, updateHandles.get(i).getResultAsync().get());
    }
  }

  @WorkflowInterface
  public interface WorkflowWithUpdate {

    @WorkflowMethod
    String execute();

    @UpdateMethod
    String update(String value);

    @UpdateValidatorMethod(updateName = "update")
    void updateValidator(String value);

    @SignalMethod
    void tryComplete();
  }

  public static class TestUpdateWorkflowImpl implements WorkflowWithUpdate {
    List<String> updates = new ArrayList<>();
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      Workflow.await(() -> Workflow.isEveryHandlerFinished());
      return updates.stream().reduce("", (a, b) -> a + " " + b);
    }

    @Override
    public void tryComplete() {
      promise.complete(null);
    }

    @Override
    public String update(String value) {
      promise.get();
      updates.add(value);
      Workflow.sleep(Duration.ofSeconds(5));
      return value;
    }

    @Override
    public void updateValidator(String value) {
      if (Workflow.isEveryHandlerFinished()) {
        throw new IllegalArgumentException("Workflow.isEveryHandlerFinished() should return false");
      }
    }
  }
}
