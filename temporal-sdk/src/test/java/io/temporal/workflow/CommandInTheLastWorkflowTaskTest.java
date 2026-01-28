package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.Objects;
import org.junit.Rule;
import org.junit.Test;

public class CommandInTheLastWorkflowTaskTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  @Test
  public void testCommandInTheLastWorkflowTask() {
    TestWorkflows.TestWorkflowReturnString client =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class);
    assertEquals("done", client.execute());
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflowReturnString {

    @Override
    public String execute() {
      Async.procedure(
          () -> {
            Workflow.mutableSideEffect(
                "id1", Integer.class, (o, n) -> Objects.equals(n, o), () -> 0);
          });
      return "done";
    }
  }
}
