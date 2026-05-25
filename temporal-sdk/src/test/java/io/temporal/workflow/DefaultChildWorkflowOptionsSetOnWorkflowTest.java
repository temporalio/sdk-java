package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class DefaultChildWorkflowOptionsSetOnWorkflowTest {

  private static final Duration DEFAULT_WORKFLOW_EXECUTION_TIMEOUT = Duration.ofSeconds(100);
  private static final Duration PER_TYPE_WORKFLOW_EXECUTION_TIMEOUT = Duration.ofSeconds(200);

  private static final ChildWorkflowOptions defaultChildWorkflowOptions =
      ChildWorkflowOptions.newBuilder()
          .setWorkflowExecutionTimeout(DEFAULT_WORKFLOW_EXECUTION_TIMEOUT)
          .build();

  private static final ChildWorkflowOptions perTypeChildWorkflowOptions =
      ChildWorkflowOptions.newBuilder()
          .setWorkflowExecutionTimeout(PER_TYPE_WORKFLOW_EXECUTION_TIMEOUT)
          .build();

  private static final Map<String, ChildWorkflowOptions> childWorkflowOptionsMap =
      Collections.singletonMap("TestWorkflow1", perTypeChildWorkflowOptions);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setDefaultChildWorkflowOptions(defaultChildWorkflowOptions)
                  .setChildWorkflowOptions(childWorkflowOptionsMap)
                  .build(),
              ParentWorkflowImpl.class,
              ChildWorkflowImpl.class)
          .build();

  @Test
  public void testDefaultChildWorkflowOptionsApplied() {
    TestWorkflowReturnString workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflowReturnString.class,
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    String result = workflowStub.execute();
    assertEquals("child_done", result);
  }

  public static class ParentWorkflowImpl implements TestWorkflowReturnString {
    @Override
    public String execute() {
      // This uses the per-type options from WorkflowImplementationOptions
      // because the workflow type "TestWorkflow1" has specific options set.
      TestWorkflow1 child = Workflow.newChildWorkflowStub(TestWorkflow1.class);
      return child.execute("input");
    }
  }

  public static class ChildWorkflowImpl implements TestWorkflow1 {
    @Override
    public String execute(String arg) {
      return "child_done";
    }
  }
}
