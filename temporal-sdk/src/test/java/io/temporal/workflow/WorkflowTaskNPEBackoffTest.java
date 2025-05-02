package io.temporal.workflow;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowTaskNPEBackoffTest {

  private static int testWorkflowTaskFailureBackoffReplayCount;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowTaskNPEBackoff.class).build();

  @Test
  public void testWorkflowTaskNPEBackoff() {
    testWorkflowTaskFailureBackoffReplayCount = 0;
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(10))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    TestWorkflow1 workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    long start = testWorkflowRule.getTestEnvironment().currentTimeMillis();
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    long elapsed = testWorkflowRule.getTestEnvironment().currentTimeMillis() - start;
    Assert.assertTrue("spinned on fail workflow task", elapsed > 1000);
    Assert.assertEquals("result1", result);
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    Assert.assertEquals(
        1,
        testWorkflowRule
            .getHistoryEvents(execution.getWorkflowId(), EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED)
            .size());
  }

  public static class TestWorkflowTaskNPEBackoff implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      if (testWorkflowTaskFailureBackoffReplayCount++ < 2) {
        throw new NullPointerException("simulated workflow task failure");
      }
      return "result1";
    }
  }
}
