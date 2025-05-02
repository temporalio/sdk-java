package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowInitConstructorTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestInitWorkflow.class).build();

  @Test
  public void testInit() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertEquals(testWorkflowRule.getTaskQueue(), result);
  }

  public static class TestInitWorkflow implements TestWorkflow1 {

    public TestInitWorkflow() {}

    public TestInitWorkflow(String taskQueue) {
      Assert.fail();
    }

    @Override
    public String execute(String taskQueue) {
      return taskQueue;
    }
  }
}
