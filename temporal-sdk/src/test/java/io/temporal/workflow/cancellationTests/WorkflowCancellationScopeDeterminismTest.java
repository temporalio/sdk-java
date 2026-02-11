package io.temporal.workflow.cancellationTests;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowCancellationScopeDeterminismTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new TestActivityImpl())
          .build();

  @Test(timeout = 60000)
  public void replayCanceledWorkflow() throws Exception {
    for (int i = 0; i < 100; i++) {
      TestWorkflow testWorkflow = testWorkflowRule.newWorkflowStub(TestWorkflow.class);

      WorkflowClient.start(testWorkflow::start);

      WorkflowStub stub = WorkflowStub.fromTyped(testWorkflow);
      stub.cancel();
      try {
        stub.getResult(Void.class);
      } catch (Exception e) {
        // ignore; just blocking to make sure workflow is actually finished
      }

      WorkflowExecutionHistory history =
          testWorkflowRule
              .getWorkflowClient()
              .fetchHistory(stub.getExecution().getWorkflowId(), stub.getExecution().getRunId());
      WorkflowReplayer.replayWorkflowExecution(history, testWorkflowRule.getWorker());
    }
  }

  @Test
  public void replayTest() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "cancellationScopeDeterminism.json", TestWorkflowImpl.class);
  }

  @Test
  public void replayBackwardCompatibilityTest() throws Exception {
    // This test validates that a workflow which started before the
    // DETERMINISTIC_CANCELLATION_SCOPE_ORDER
    // flag was added to initialFlags will replay correctly without hitting NDE issues
    // The workflow history was recorded without the flag, so it should replay successfully
    // when the flag is in the initial set because the flag logic respects historical workflows
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "cancellationScopeDeterminism_beforeFlag.json", TestWorkflowImpl.class);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void start();
  }

  @ActivityInterface
  public interface TestActivity {
    void doActivity();
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public void doActivity() {
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static class TestWorkflowImpl implements TestWorkflow {

    TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(60)).build());

    @Override
    public void start() {
      CancellationScope scope = Workflow.newCancellationScope(() -> activity.doActivity());

      Async.procedure(
          () -> {
            Workflow.sleep(Duration.ofMinutes(5));
          });

      scope.run();
    }
  }
}
