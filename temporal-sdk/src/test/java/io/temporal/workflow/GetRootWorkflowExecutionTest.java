package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

public class GetRootWorkflowExecutionTest {
  private static final TestActivities.VariousTestActivities activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void getRootWorkflowExecution() {
    TestWorkflows.TestWorkflowReturnString workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.TestWorkflowReturnString.class);
    String workflowId = workflowStub.execute();
    assertEquals(
        "empty " + WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId(), workflowId);
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflowReturnString {

    @Override
    public String execute() {
      String result = Workflow.getInfo().getRootWorkflowId().orElse("empty");
      if (!Workflow.getInfo().getParentWorkflowId().isPresent()) {
        result += " ";
        result +=
            Workflow.newChildWorkflowStub(TestWorkflows.TestWorkflowReturnString.class).execute();
      }
      return result;
    }
  }
}
