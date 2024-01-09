package io.temporal.workflow.updateTest;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class UpdateAnnotationTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(WorkflowImpl.class).build();

  @Test
  public void testUpdateOnlyInterface() {
    // Verify a stub to an interface with only @UpdateMethod can be created.
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .build();
    WorkflowTestInterface workflow =
        workflowClient.newWorkflowStub(WorkflowTestInterface.class, options);

    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    UpdateWorkflowInterface updateOnlyWorkflow =
        workflowClient.newWorkflowStub(UpdateWorkflowInterface.class, execution.getWorkflowId());
    updateOnlyWorkflow.update();

    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals("success", result);
  }

  public interface UpdateWorkflowInterface {
    @UpdateMethod
    void update();
  }

  @WorkflowInterface
  public interface WorkflowTestInterface extends UpdateWorkflowInterface {
    @WorkflowMethod
    String execute();
  }

  public static class WorkflowImpl implements WorkflowTestInterface {
    boolean complete = false;

    @Override
    public String execute() {
      Workflow.await(() -> complete);
      return "success";
    }

    @Override
    public void update() {
      complete = true;
    }
  }
}
