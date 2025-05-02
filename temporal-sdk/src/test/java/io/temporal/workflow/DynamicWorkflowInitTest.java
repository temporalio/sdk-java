package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowFailedException;
import io.temporal.common.converter.EncodedValues;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class DynamicWorkflowInitTest {

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

  @Test
  public void testInitThrowApplicationFailure() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException failure =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(""));
    Assert.assertTrue(failure.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) failure.getCause();
    assertEquals("Empty taskQueue", applicationFailure.getOriginalMessage());
  }

  public static class TestInitWorkflow implements DynamicWorkflow {
    private final String taskQueue;

    @WorkflowInit
    public TestInitWorkflow(EncodedValues args) {
      String taskQueue = args.get(0, String.class);
      if (taskQueue.isEmpty()) {
        throw ApplicationFailure.newFailure("Empty taskQueue", "TestFailure");
      }
      this.taskQueue = taskQueue;
    }

    @Override
    public Object execute(EncodedValues args) {
      String taskQueue = args.get(0, String.class);
      if (!taskQueue.equals(this.taskQueue)) {
        throw new IllegalArgumentException("Unexpected taskQueue: " + taskQueue);
      }
      return taskQueue;
    }
  }
}
