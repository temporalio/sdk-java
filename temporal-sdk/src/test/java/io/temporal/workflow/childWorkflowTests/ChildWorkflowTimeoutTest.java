package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Throwables;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestChild;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow3;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowTimeoutTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflowWithChildTimeout.class, TestChild.class)
          .build();

  @Test
  public void testChildWorkflowTimeout() {
    TestWorkflow1 client = testWorkflowRule.newWorkflowStub200sTimeoutOptions(TestWorkflow1.class);
    String result = client.execute(testWorkflowRule.getTaskQueue());
    assertTrue(result, result.contains("ChildWorkflowFailure"));
    assertTrue(result, result.contains("TimeoutFailure"));
  }

  public static class TestParentWorkflowWithChildTimeout implements TestWorkflow1 {

    private final TestWorkflow3 child;

    public TestParentWorkflowWithChildTimeout() {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setWorkflowRunTimeout(Duration.ofSeconds(1)).build();
      child = Workflow.newChildWorkflowStub(TestWorkflow3.class, options);
    }

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowFailure failure =
          assertThrows(
              ChildWorkflowFailure.class,
              () -> child.execute("Hello ", (int) Duration.ofDays(1).toMillis()));
      assertTrue(failure.getCause() instanceof TimeoutFailure);
      return Throwables.getStackTraceAsString(failure);
    }
  }
}
