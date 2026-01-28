package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Rule;
import org.junit.Test;

public class ExternalWorkflowGetExecutionTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ParentWorkflowImpl.class, ChildWorkflowImpl.class)
          .build();

  @Test
  public void testGetExecutionFromExternalWorkflowStub() {
    ParentWorkflow client =
        testWorkflowRule.newWorkflowStub200sTimeoutOptions(ParentWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(client::execute);
    String result = WorkflowStub.fromTyped(client).getResult(String.class);
    assertEquals(execution.getWorkflowId(), result);
  }

  @WorkflowInterface
  public interface ParentWorkflow {
    @WorkflowMethod
    String execute();
  }

  @WorkflowInterface
  public interface ChildWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class ParentWorkflowImpl implements ParentWorkflow {
    @Override
    public String execute() {
      ChildWorkflow child = Workflow.newChildWorkflowStub(ChildWorkflow.class);
      return child.execute();
    }
  }

  public static class ChildWorkflowImpl implements ChildWorkflow {
    @Override
    public String execute() {
      String parentId = Workflow.getInfo().getParentWorkflowId().get();
      ParentWorkflow parent = Workflow.newExternalWorkflowStub(ParentWorkflow.class, parentId);
      return Workflow.getWorkflowExecution(parent).get().getWorkflowId();
    }
  }
}
