package io.temporal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

public class CountWorkflowsTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflows.DoNothingNoArgsWorkflow.class)
          .build();

  @Test
  public void countWorkflowExecutions_returnsAllExecutions() throws InterruptedException {
    assumeTrue(
        "Test Server doesn't support countWorkflowExecutions endpoint yet",
        SDKTestWorkflowRule.useExternalService);

    final int EXECUTIONS_COUNT = 5;

    for (int i = 0; i < EXECUTIONS_COUNT; i++) {
      WorkflowStub.fromTyped(testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class))
          .start();
    }

    // Visibility API may be eventual consistent
    Thread.sleep(4_000);

    String queryString =
        "TaskQueue='" + testWorkflowRule.getTaskQueue() + "' GROUP BY ExecutionStatus";
    WorkflowExecutionCount count = testWorkflowRule.getWorkflowClient().countWorkflows(queryString);
    assertEquals(EXECUTIONS_COUNT, count.getCount());
    assertEquals(1, count.getGroups().size());
    assertEquals(5, count.getGroups().get(0).getCount());
    assertEquals("Completed", count.getGroups().get(0).getGroupValues().get(0).get(0));
  }
}
