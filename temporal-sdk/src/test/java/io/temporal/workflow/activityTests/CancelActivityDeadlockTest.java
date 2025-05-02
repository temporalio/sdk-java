package io.temporal.workflow.activityTests;

import static org.junit.Assert.*;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.Issue;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

/** This test was hanging before the fix for deadlock was implemented */
@Issue("https://github.com/temporalio/sdk-java/issues/871")
public class CancelActivityDeadlockTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ScheduleCancelActivityWorkflow.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void shouldNotCauseDeadlock() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(8))
            .setWorkflowTaskTimeout(Duration.ofSeconds(5))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);

    WorkflowStub.fromTyped(workflowStub).start("input");
    assertThrows(
        WorkflowFailedException.class,
        () -> WorkflowStub.fromTyped(workflowStub).getResult(String.class));
  }

  public static class ScheduleCancelActivityWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities.VariousTestActivities activities =
          Workflow.newActivityStub(
              TestActivities.VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
      CancellationScope cancellationScope =
          Workflow.newCancellationScope(() -> Async.procedure(activities::activity1, 1));
      cancellationScope.run();
      try {
        // Forcing an end of WFT
        Workflow.sleep(1000);
        throw ApplicationFailure.newNonRetryableFailure("messsage", "type");
      } finally {
        // this code causes a triggering of event loop when the main workflow method already thew
        // and the executor is shutting down the runner
        cancellationScope.cancel();
      }
    }
  }
}
