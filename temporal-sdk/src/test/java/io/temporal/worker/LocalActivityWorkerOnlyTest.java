package io.temporal.worker;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestActivities.NoArgsActivity;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityWorkerOnlyTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setActivityImplementations(new TestActivityImpl())
          .setWorkflowTypes(ActivityWorkflowImpl.class, LocalActivityWorkflowImpl.class)
          .setWorkerOptions(WorkerOptions.newBuilder().setLocalActivityWorkerOnly(true).build())
          .build();

  @Test
  public void verifyThatLocalActivitiesAreExecuted() {
    LocalActivityWorkflow localActivityWorkflow =
        testWorkflowRule.newWorkflowStub(LocalActivityWorkflow.class);
    localActivityWorkflow.callLocalActivity();
  }

  @Test
  public void verifyThatNormalActivitiesAreTimedOut() {
    NoArgsWorkflow activityWorkflow = testWorkflowRule.newWorkflowStub(NoArgsWorkflow.class);
    WorkflowFailedException workflowFailedException =
        assertThrows(WorkflowFailedException.class, activityWorkflow::execute);
    assertThat(workflowFailedException.getCause().getCause(), instanceOf(TimeoutFailure.class));
  }

  @WorkflowInterface
  public interface LocalActivityWorkflow {
    @WorkflowMethod
    void callLocalActivity();
  }

  public static class TestActivityImpl implements NoArgsActivity {
    @Override
    public void execute() {}
  }

  public static class LocalActivityWorkflowImpl implements LocalActivityWorkflow {

    @Override
    public void callLocalActivity() {
      NoArgsActivity activity =
          Workflow.newLocalActivityStub(
              NoArgsActivity.class,
              LocalActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .build());
      activity.execute();
    }
  }

  public static class ActivityWorkflowImpl implements NoArgsWorkflow {

    @Override
    public void execute() {
      NoArgsActivity activity =
          Workflow.newActivityStub(
              NoArgsActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .build());
      activity.execute();
    }
  }
}
