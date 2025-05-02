package io.temporal.workflow.activityTests;

import static org.junit.Assert.*;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.Duration;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * If Local Activity throws an {@link Error}, it should immediately fail Workflow Task. Java Error
 * signals a problem with the Worker and shouldn't lead to a failure of a Local Activity execution
 * or a Workflow.
 */
public class LocalActivityThrowingErrorTest {

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(LocalActivityThrowsErrorWorkflow.class)
          .setActivityImplementations(new ApplicationFailureActivity())
          .build();

  @Test
  public void throwsError() {
    NoArgsWorkflow workflow = testWorkflowRule.newWorkflowStub(NoArgsWorkflow.class);
    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);
    workflowStub.start();
    WorkflowExecution execution = workflowStub.getExecution();
    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    List<HistoryEvent> historyEvents =
        testWorkflowRule.getHistoryEvents(
            execution.getWorkflowId(), EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
    assertTrue(historyEvents.size() > 0);
  }

  public static class LocalActivityThrowsErrorWorkflow implements NoArgsWorkflow {

    private final TestActivities.NoArgsActivity activity1 =
        Workflow.newLocalActivityStub(
            TestActivities.NoArgsActivity.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .build());

    @Override
    public void execute() {
      activity1.execute();
    }
  }

  public static class ApplicationFailureActivity implements TestActivities.NoArgsActivity {
    @Override
    public void execute() {
      throw new Error("test");
    }
  }
}
