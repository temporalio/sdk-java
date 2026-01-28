package io.temporal.workflow.activityTests.cancellation;

import static java.lang.Thread.sleep;
import static org.junit.Assert.*;

import io.temporal.activity.*;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.Issue;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;

/**
 * There was a bug when SDK were immediately triggering event loop if {@link
 * EventType#EVENT_TYPE_ACTIVITY_TASK_CANCELED} is observed during matching of server events. This
 * led to premature triggering of event loop before all the events were matched and the event loop
 * was triggered by {@link EventType#EVENT_TYPE_WORKFLOW_TASK_STARTED}. This may lead to determinism
 * issues on replay. It also manifests as code executes first time on this premature event loop
 * trigger, but sees {@link WorkflowUnsafe#isReplaying} flag set to true.
 */
@Issue("https://github.com/temporalio/sdk-java/issues/1558")
public class ActivityCancellationEventReplayTest {
  private static final Signal activityStarted = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ReplayFlagReportingWorkflow.class)
          .setActivityImplementations(new MyActivityImpl())
          .build();

  @Test
  public void workflowShouldObserveNonReplayingStateIfActivityWasCancelled()
      throws InterruptedException {
    TestWorkflows.NoArgsWorkflow wf =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
    WorkflowStub stub = WorkflowStub.fromTyped(wf);
    stub.start();
    activityStarted.waitForSignal();
    stub.cancel();
    assertThrows(WorkflowFailedException.class, () -> stub.getResult(Void.class));
    assertEquals(
        "When we first time execute code isReplaying flag should never be True",
        Boolean.FALSE,
        ReplayFlagReportingWorkflow.wasReplayingEqTrueOnFirstExecution);
  }

  public static class ReplayFlagReportingWorkflow implements TestWorkflows.NoArgsWorkflow {
    public static AtomicBoolean firstTimeExecutingThisCode = new AtomicBoolean(true);
    public static Boolean wasReplayingEqTrueOnFirstExecution;

    @Override
    public void execute() {
      TestActivities.NoArgsActivity activity =
          Workflow.newActivityStub(
              TestActivities.NoArgsActivity.class,
              ActivityOptions.newBuilder()
                  .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                  .setHeartbeatTimeout(Duration.ofSeconds(2))
                  .setStartToCloseTimeout(Duration.ofSeconds(10))
                  .validateAndBuildWithDefaults());
      try {
        activity.execute();
      } catch (Exception e) {
        if (firstTimeExecutingThisCode.compareAndSet(true, false)) {
          wasReplayingEqTrueOnFirstExecution = WorkflowUnsafe.isReplaying();
        }
      }
    }
  }

  public static class MyActivityImpl implements TestActivities.NoArgsActivity {
    @Override
    public void execute() {
      activityStarted.signal();
      while (true) {
        Activity.getExecutionContext().heartbeat("");
        try {
          sleep(200L);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
    }
  }
}
