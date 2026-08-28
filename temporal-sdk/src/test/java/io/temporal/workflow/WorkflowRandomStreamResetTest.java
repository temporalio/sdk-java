package io.temporal.workflow;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowRandomStreamResetTest {
  private static final String STREAM_NAME = "io.temporal.test";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ResetWorkflowImpl.class, LateStreamResetWorkflowImpl.class)
          .setActivityImplementations(new BoundaryActivityImpl())
          .build();

  @Test
  public void resetReseedsHeldStreamAfterResetPoint() {
    assumeTrue(
        "Test Server doesn't support reset workflow", SDKTestWorkflowRule.useExternalService);
    assertResetValues(ResetWorkflow.class);
  }

  @Test
  public void streamCreatedAfterResetPointUsesNewRun() {
    assumeTrue(
        "Test Server doesn't support reset workflow", SDKTestWorkflowRule.useExternalService);
    assertResetValues(LateStreamResetWorkflow.class);
  }

  private <T> void assertResetValues(Class<T> workflowType) {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    T workflow = client.newWorkflowStub(workflowType, options);
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    stub.start();
    long[] original = stub.getResult(long[].class);

    WorkflowExecution execution = stub.getExecution();
    WorkflowExecutionHistory history = client.fetchHistory(workflowId);
    long resetEventId =
        history.getEvents().stream()
            .filter(event -> event.getEventType() == EVENT_TYPE_WORKFLOW_TASK_COMPLETED)
            .mapToLong(event -> event.getEventId())
            .max()
            .orElseThrow(IllegalStateException::new);

    @SuppressWarnings("deprecation")
    ResetWorkflowExecutionResponse response =
        client
            .getWorkflowServiceStubs()
            .blockingStub()
            .resetWorkflowExecution(
                ResetWorkflowExecutionRequest.newBuilder()
                    .setNamespace(SDKTestWorkflowRule.NAMESPACE)
                    .setWorkflowExecution(execution)
                    .setWorkflowTaskFinishEventId(resetEventId)
                    .setReason("Integration test")
                    .setRequestId(UUID.randomUUID().toString())
                    .build());

    T resetWorkflow =
        client.newWorkflowStub(
            workflowType,
            WorkflowTargetOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setRunId(response.getRunId())
                .build());
    long[] afterReset = WorkflowStub.fromTyped(resetWorkflow).getResult(long[].class);

    assertEquals(original[0], afterReset[0]);
    assertNotEquals(original[1], afterReset[1]);
  }

  @WorkflowInterface
  public interface ResetWorkflow {
    @WorkflowMethod
    long[] run();
  }

  @WorkflowInterface
  public interface LateStreamResetWorkflow {
    @WorkflowMethod
    long[] run();
  }

  @ActivityInterface
  public interface BoundaryActivity {
    @ActivityMethod
    void run();
  }

  public static class ResetWorkflowImpl implements ResetWorkflow {
    private final BoundaryActivity activity =
        Workflow.newActivityStub(
            BoundaryActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public long[] run() {
      WorkflowRandomStream random = Workflow.getRandomStream(STREAM_NAME);
      long first = random.nextLong();
      activity.run();
      long second = random.nextLong();
      return new long[] {first, second};
    }
  }

  public static class LateStreamResetWorkflowImpl implements LateStreamResetWorkflow {
    private final BoundaryActivity activity =
        Workflow.newActivityStub(
            BoundaryActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public long[] run() {
      long first = Workflow.getRandomStream("before-reset").nextLong();
      activity.run();
      long second = Workflow.getRandomStream(STREAM_NAME).nextLong();
      return new long[] {first, second};
    }
  }

  public static class BoundaryActivityImpl implements BoundaryActivity {
    @Override
    public void run() {}
  }
}
