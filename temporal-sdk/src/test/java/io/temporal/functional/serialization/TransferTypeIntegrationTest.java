package io.temporal.functional.serialization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.TransferTypeTestModel;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class TransferTypeIntegrationTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TransferWorkflowImpl.class, ActivityTransferWorkflowImpl.class)
          .setActivityImplementations(new TransferActivityImpl())
          .build();

  @Test
  public void workflowClientAndWorkerRoundTripTransferTypes() {
    TransferWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TransferWorkflow.class);

    TransferTypeTestModel result = workflow.execute(new TransferTypeTestModel("input"));

    assertEquals(new TransferTypeTestModel("input-workflow"), result);
    assertTrue(result.wasTransferred());
  }

  @Test
  public void workflowHistoryWithTransferTypesReplays() throws Exception {
    TransferWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TransferWorkflow.class);
    workflow.execute(new TransferTypeTestModel("replay"));
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    WorkflowExecutionHistory history =
        testWorkflowRule.getExecutionHistory(
            untyped.getExecution().getWorkflowId(), untyped.getExecution().getRunId());

    WorkflowReplayer.replayWorkflowExecution(history, testWorkflowRule.getWorker());
  }

  @Test
  public void activityStubRoundTripsTransferTypes() {
    ActivityTransferWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ActivityTransferWorkflow.class);

    TransferTypeTestModel result = workflow.execute(new TransferTypeTestModel("input"));

    assertEquals(new TransferTypeTestModel("input-activity-workflow"), result);
    assertTrue(result.wasTransferred());
  }

  @WorkflowInterface
  public interface TransferWorkflow {
    @WorkflowMethod
    TransferTypeTestModel execute(TransferTypeTestModel input);
  }

  public static final class TransferWorkflowImpl implements TransferWorkflow {
    @Override
    public TransferTypeTestModel execute(TransferTypeTestModel input) {
      if (!input.wasTransferred()) {
        throw new IllegalStateException("Workflow input did not use its transfer type converter");
      }
      return new TransferTypeTestModel(input.value() + "-workflow");
    }
  }

  @WorkflowInterface
  public interface ActivityTransferWorkflow {
    @WorkflowMethod
    TransferTypeTestModel execute(TransferTypeTestModel input);
  }

  public static final class ActivityTransferWorkflowImpl implements ActivityTransferWorkflow {
    private final TransferActivity activity =
        Workflow.newActivityStub(TransferActivity.class, options());

    @Override
    public TransferTypeTestModel execute(TransferTypeTestModel input) {
      if (!input.wasTransferred()) {
        throw new IllegalStateException("Workflow input did not use its transfer type converter");
      }
      TransferTypeTestModel result = activity.execute(input);
      if (!result.wasTransferred()) {
        throw new IllegalStateException("Activity result did not use its transfer type converter");
      }
      return new TransferTypeTestModel(result.value() + "-workflow");
    }
  }

  @ActivityInterface
  public interface TransferActivity {
    @ActivityMethod
    TransferTypeTestModel execute(TransferTypeTestModel input);
  }

  public static final class TransferActivityImpl implements TransferActivity {
    @Override
    public TransferTypeTestModel execute(TransferTypeTestModel input) {
      if (!input.wasTransferred()) {
        throw new IllegalStateException("Activity input did not use its transfer type converter");
      }
      return new TransferTypeTestModel(input.value() + "-activity");
    }
  }

  private static ActivityOptions options() {
    return ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
        .build();
  }
}
