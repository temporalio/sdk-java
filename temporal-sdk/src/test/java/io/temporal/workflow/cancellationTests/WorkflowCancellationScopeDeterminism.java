package io.temporal.workflow.cancellationTests;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowCancellationScopeDeterminism {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new TestActivityImpl())
          .setUseExternalService(true)
          .build();

  @Before
  public void setUp() {
    WorkflowStateMachines.initialFlags =
        Collections.unmodifiableList(Arrays.asList(SdkFlag.DETERMINISTIC_CANCELLATION_SCOPE_ORDER));
  }

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
