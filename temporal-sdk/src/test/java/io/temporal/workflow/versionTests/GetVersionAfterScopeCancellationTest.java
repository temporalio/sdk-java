package io.temporal.workflow.versionTests;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.Issue;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

/**
 * This test provides a localized reproduction for a state machine issue #615 This test has a
 * corresponding state machine unit test {@link
 * io.temporal.internal.statemachines.VersionStateMachineTest#testRecordAfterCommandCancellation}
 */
@Issue("https://github.com/temporalio/sdk-java/issues/615")
public class GetVersionAfterScopeCancellationTest extends BaseVersionTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(getDefaultWorkflowImplementationOptions(), ReminderWorkflowImpl.class)
          .setWorkerOptions(WorkerOptions.newBuilder().build())
          .build();

  public GetVersionAfterScopeCancellationTest(
      boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Test
  public void testGetVersionAndCancelTimer() {
    ReminderWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ReminderWorkflow.class);

    WorkflowClient.start(workflowStub::start);
    workflowStub.signal();

    WorkflowStub untypedWorkflowStub = WorkflowStub.fromTyped(workflowStub);
    untypedWorkflowStub.getResult(Void.TYPE);

    testWorkflowRule.assertNoHistoryEvent(
        untypedWorkflowStub.getExecution().getWorkflowId(), EVENT_TYPE_WORKFLOW_TASK_FAILED);
  }

  @WorkflowInterface
  public interface ReminderWorkflow {

    @WorkflowMethod
    void start();

    @SignalMethod
    void signal();
  }

  public static final class ReminderWorkflowImpl implements ReminderWorkflow {

    @Override
    public void start() {
      Workflow.sleep(Duration.ofSeconds(7));
    }

    @Override
    public void signal() {
      CancellationScope activeScope1 =
          Workflow.newCancellationScope(() -> Workflow.newTimer(Duration.ofHours(4)));
      activeScope1.run();

      Workflow.getVersion("some-change", Workflow.DEFAULT_VERSION, 1);

      activeScope1.cancel();

      // it's critical for this duration to be short for the test to fail originally (4s or less)
      Duration secondScopeTimerDuration = Duration.ofSeconds(2);
      Workflow.newTimer(secondScopeTimerDuration);
    }
  }
}
