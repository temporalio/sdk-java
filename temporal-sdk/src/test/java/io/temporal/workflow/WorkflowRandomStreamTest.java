package io.temporal.workflow;

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
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowRandomStreamTest {
  private static final String STREAM_NAME = "io.temporal.test";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              SimpleWorkflowImpl.class,
              ReplayWorkflowImpl.class,
              ResetWorkflowImpl.class,
              ResetLateSourceWorkflowImpl.class,
              ContinueAsNewWorkflowImpl.class,
              ParentWorkflowImpl.class)
          .setActivityImplementations(new SimpleActivityImpl())
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void noCollisionAcrossRuns() {
    SimpleWorkflow first = testWorkflowRule.newWorkflowStubTimeoutOptions(SimpleWorkflow.class);
    SimpleWorkflow second = testWorkflowRule.newWorkflowStubTimeoutOptions(SimpleWorkflow.class);

    assertNotEquals(first.run(), second.run());
  }

  @Test
  public void deterministicReplay() {
    ReplayWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(ReplayWorkflow.class);

    long result = workflow.run();

    assertEquals(result, workflow.currentState());
  }

  @Test
  public void resetReproducesValues() {
    assumeTrue(
        "Test Server doesn't support reset workflow", SDKTestWorkflowRule.useExternalService);
    assertResetValues(ResetWorkflow.class);
  }

  @Test
  public void resetReseedsSourceCreatedAfterResetPoint() {
    assumeTrue(
        "Test Server doesn't support reset workflow", SDKTestWorkflowRule.useExternalService);
    assertResetValues(ResetLateSourceWorkflow.class);
  }

  @Test
  public void continueAsNewDrawsNewValues() {
    ContinueAsNewWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ContinueAsNewWorkflow.class);

    long[] values = workflow.run(null);

    assertEquals(2, values.length);
    assertNotEquals(values[0], values[1]);
  }

  @Test
  public void childContinueAsNewDrawsNewValues() {
    ParentWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(ParentWorkflow.class);

    long[] values = workflow.run();

    assertEquals(3, values.length);
    assertNotEquals(values[0], values[1]);
    assertNotEquals(values[0], values[2]);
    assertNotEquals(values[1], values[2]);
  }

  private void assertResetValues(Class<?> workflowType) {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    WorkflowStub stub =
        WorkflowStub.fromTyped(testWorkflowRule.newWorkflowStubTimeoutOptions(workflowType));
    WorkflowExecution execution = stub.start();
    long[] original = stub.getResult(long[].class);
    assertEquals(2, original.length);
    assertNotEquals(original[0], original[1]);

    // The reset targets the second Workflow Task (id=10), so the first draw is replayed and the
    // second draw is redrawn
    ResetWorkflowExecutionResponse response =
        client
            .getWorkflowServiceStubs()
            .blockingStub()
            .resetWorkflowExecution(
                ResetWorkflowExecutionRequest.newBuilder()
                    .setNamespace(SDKTestWorkflowRule.NAMESPACE)
                    .setWorkflowExecution(execution)
                    .setWorkflowTaskFinishEventId(10)
                    .setReason("Integration test")
                    .setRequestId(UUID.randomUUID().toString())
                    .build());

    long[] afterReset =
        client
            .newUntypedWorkflowStub(
                WorkflowTargetOptions.newBuilder()
                    .setWorkflowId(execution.getWorkflowId())
                    .setRunId(response.getRunId())
                    .build())
            .getResult(long[].class);
    assertEquals(2, afterReset.length);
    assertNotEquals(afterReset[0], afterReset[1]);

    assertEquals(original[0], afterReset[0]);
    assertNotEquals(original[1], afterReset[1]);
  }

  @WorkflowInterface
  public interface SimpleWorkflow {
    @WorkflowMethod
    long run();
  }

  @WorkflowInterface
  public interface ReplayWorkflow {
    @WorkflowMethod
    long run();

    @QueryMethod
    long currentState();
  }

  @WorkflowInterface
  public interface ResetWorkflow {
    @WorkflowMethod
    long[] run();
  }

  @WorkflowInterface
  public interface ResetLateSourceWorkflow {
    @WorkflowMethod
    long[] run();
  }

  @WorkflowInterface
  public interface ContinueAsNewWorkflow {
    @WorkflowMethod
    long[] run(Long previous);
  }

  @WorkflowInterface
  public interface ParentWorkflow {
    @WorkflowMethod
    long[] run();
  }

  @ActivityInterface
  public interface SimpleActivity {
    @ActivityMethod
    void run();
  }

  public static class SimpleWorkflowImpl implements SimpleWorkflow {
    @Override
    public long run() {
      return Workflow.getRandomStream(STREAM_NAME).nextLong();
    }
  }

  public static class ReplayWorkflowImpl implements ReplayWorkflow {
    private long state;

    @Override
    public long run() {
      Random random = Workflow.getRandomStream(STREAM_NAME);
      state = random.nextLong();
      newSimpleActivity().run();
      state = random.nextLong();
      return state;
    }

    @Override
    public long currentState() {
      return state;
    }
  }

  public static class ResetWorkflowImpl implements ResetWorkflow {
    @Override
    public long[] run() {
      Random random = Workflow.getRandomStream(STREAM_NAME);
      long first = random.nextLong();
      newSimpleActivity().run();
      long second = random.nextLong();
      return new long[] {first, second};
    }
  }

  public static class ResetLateSourceWorkflowImpl implements ResetLateSourceWorkflow {
    @Override
    public long[] run() {
      long first = Workflow.getRandomStream("other").nextLong();
      newSimpleActivity().run();
      long second = Workflow.getRandomStream(STREAM_NAME).nextLong();
      return new long[] {first, second};
    }
  }

  public static class ContinueAsNewWorkflowImpl implements ContinueAsNewWorkflow {
    @Override
    public long[] run(Long previous) {
      long current = Workflow.getRandomStream(STREAM_NAME).nextLong();
      if (previous == null) {
        Workflow.continueAsNew(current);
      }
      return new long[] {previous, current};
    }
  }

  public static class ParentWorkflowImpl implements ParentWorkflow {
    @Override
    public long[] run() {
      long parent = Workflow.getRandomStream(STREAM_NAME).nextLong();
      long[] child = Workflow.newChildWorkflowStub(ContinueAsNewWorkflow.class).run(null);
      return new long[] {parent, child[0], child[1]};
    }
  }

  public static class SimpleActivityImpl implements SimpleActivity {
    @Override
    public void run() {}
  }

  private static SimpleActivity newSimpleActivity() {
    return Workflow.newActivityStub(
        SimpleActivity.class,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(1)).build());
  }
}
