package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowDescribeTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestInitWorkflow.class).build();

  @Test
  public void testWorkflowDescribe() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertEquals(testWorkflowRule.getTaskQueue(), result);
    WorkflowExecutionDescription description = WorkflowStub.fromTyped(workflowStub).describe();
    assertEquals(testWorkflowRule.getTaskQueue(), description.getTaskQueue());
    assertEquals("TestWorkflow1", description.getWorkflowType());
    assertEquals(WorkflowStub.fromTyped(workflowStub).getExecution(), description.getExecution());
  }

  public static class TestInitWorkflow implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      return taskQueue;
    }
  }
}
