package io.temporal.workflow.activityTests;

import static org.junit.Assert.fail;

import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityIsNotRegisteredTest {
  private final VariousTestActivities activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testUnregisteredLocalActivityDoesntFailExecution() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowExecutionTimeout(Duration.ofSeconds(20))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    TestWorkflowReturnString workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflowReturnString.class, options);
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    stub.start();
    testWorkflowRule.waitForTheEndOfWFT(stub.getExecution().getWorkflowId());
    testWorkflowRule.assertHistoryEvent(
        stub.getExecution().getWorkflowId(), EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
    testWorkflowRule.assertNoHistoryEvent(
        stub.getExecution().getWorkflowId(), EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED);
    testWorkflowRule.assertNoHistoryEvent(
        stub.getExecution().getWorkflowId(),
        EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED);
  }

  public static class TestWorkflowImpl implements TestWorkflowReturnString {
    @Override
    public String execute() {
      // TestLocalActivity implementation is not registered with the worker.
      // Only VariousTestActivities does.
      TestActivities.TestLocalActivity localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.TestLocalActivity.class, SDKTestOptions.newLocalActivityOptions());

      localActivities.localActivity1();
      fail();
      return "done";
    }
  }
}
