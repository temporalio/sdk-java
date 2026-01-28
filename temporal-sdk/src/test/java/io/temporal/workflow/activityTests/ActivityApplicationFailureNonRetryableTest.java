package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertThrows;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestSignaledWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.io.IOException;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityApplicationFailureNonRetryableTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              ActivityCalledFromWorkflowMethodWorkflowImpl.class,
              ActivityCalledFromSignalMethodWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testActivityApplicationFailureNonRetryable_workflowMethod() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    verifyWorkflowFailure(
        assertThrows(
            WorkflowException.class, () -> workflowStub.execute(testWorkflowRule.getTaskQueue())));
  }

  @Test
  public void testActivityApplicationFailureNonRetryable_signal() {
    TestSignaledWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestSignaledWorkflow.class);
    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);
    workflowStub.start();
    workflow.signal("");
    verifyWorkflowFailure(
        assertThrows(WorkflowException.class, () -> workflowStub.getResult(String.class)));
  }

  /**
   * Failure of the workflow should be exactly the same, doesn't matter of the activity failure
   * happened in the workflow method or in the signal handler.
   */
  private void verifyWorkflowFailure(WorkflowException e) {
    Assert.assertTrue(e.getCause() instanceof ActivityFailure);
    Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
    Assert.assertEquals(
        "java.io.IOException", ((ApplicationFailure) e.getCause().getCause()).getType());
    Assert.assertEquals(
        RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
        ((ActivityFailure) e.getCause()).getRetryState());
    Assert.assertEquals(activitiesImpl.toString(), 1, activitiesImpl.invocations.size());
  }

  public static class ActivityCalledFromWorkflowMethodWorkflowImpl implements TestWorkflow1 {

    private final VariousTestActivities activities =
        Workflow.newActivityStub(
            VariousTestActivities.class,
            ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(200))
                .setStartToCloseTimeout(Duration.ofSeconds(1))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setMaximumInterval(Duration.ofSeconds(1))
                        .setDoNotRetry(IOException.class.getName())
                        .build())
                .build());

    @Override
    public String execute(String taskQueue) {
      activities.throwIO();
      return "ignored";
    }
  }

  public static class ActivityCalledFromSignalMethodWorkflowImpl implements TestSignaledWorkflow {

    private final VariousTestActivities activities =
        Workflow.newActivityStub(
            VariousTestActivities.class,
            ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(200))
                .setStartToCloseTimeout(Duration.ofSeconds(1))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setMaximumInterval(Duration.ofSeconds(1))
                        .setDoNotRetry(IOException.class.getName())
                        .build())
                .build());

    @Override
    public String execute() {
      Workflow.await(() -> false);
      activities.throwIO();
      return "ignored";
    }

    @Override
    public void signal(String arg) {
      activities.throwIO();
    }
  }
}
