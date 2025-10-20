package io.temporal.client;

import static org.junit.Assert.assertNotNull;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test to verify WorkflowStub.getExecution() behavior Addresses issue where getExecution() returns
 * null for stubs created with options but not yet started
 */
public class WorkflowStubGetExecutionTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl.class).build();

  @Test
  public void testGetExecutionAfterStart() {
    // Create a workflow stub with options (for starting)
    TestWorkflows.NoArgsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);

    // Start the workflow
    workflowStub.start();

    // After starting, getExecution() should not be null
    WorkflowExecution executionAfterStart = workflowStub.getExecution();
    assertNotNull("Execution should not be null after start", executionAfterStart);
    assertNotNull("WorkflowId should not be null", executionAfterStart.getWorkflowId());
    assertNotNull("RunId should not be null", executionAfterStart.getRunId());
  }

  @Test
  public void testGetExecutionForExistingWorkflow() {
    // Create and start a workflow first
    TestWorkflows.NoArgsWorkflow workflow1 =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
    WorkflowStub workflowStub1 = WorkflowStub.fromTyped(workflow1);
    WorkflowExecution startedExecution = workflowStub1.start();

    // Create a new stub bound to the existing execution
    WorkflowStub workflowStub2 =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(startedExecution, java.util.Optional.empty());

    // This should work fine since options == null
    WorkflowExecution execution = workflowStub2.getExecution();
    assertNotNull("Execution should not be null for existing workflow", execution);
    assertNotNull("WorkflowId should not be null", execution.getWorkflowId());
  }

  public static class TestWorkflowImpl implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {
      // Simple workflow that does nothing
    }
  }
}
