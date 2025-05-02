package io.temporal.workflow;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.ITestNamedChild;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestNamedChild;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ProhibitedCallsFromWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflow.class, TestNamedChild.class)
          .build();

  private static WorkflowClient workflowClient;

  @Before
  public void setUp() throws Exception {
    workflowClient = testWorkflowRule.getWorkflowClient();
  }

  @Test
  public void testWorkflowClientCallFromWorkflow() {
    NoArgsWorkflow client = testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    client.execute();
  }

  public static class TestWorkflow implements NoArgsWorkflow {
    @Override
    public void execute() {
      ITestNamedChild child = Workflow.newChildWorkflowStub(ITestNamedChild.class);
      try {
        WorkflowClient.execute(child::execute, "hello");
        fail("should be unreachable, we expect an exception");
      } catch (IllegalStateException e) {
        assertTrue(e.getMessage().startsWith("Cannot be called from workflow thread."));
      }
      try {
        WorkflowClient.start(child::execute, "world");
        fail("should be unreachable, we expect an exception");
      } catch (IllegalStateException e) {
        assertTrue(e.getMessage().startsWith("Cannot be called from workflow thread."));
      }
      try {
        // let's imagine that the workflow code somehow got a WorkflowClient instance (from DI for
        // example).
        // Let's make sure it still can't trigger it's methods
        workflowClient.getOptions();
        fail("should be unreachable, we expect an exception");
      } catch (IllegalStateException e) {
        assertTrue(e.getMessage().startsWith("Cannot be called from workflow thread."));
      }
    }
  }
}
