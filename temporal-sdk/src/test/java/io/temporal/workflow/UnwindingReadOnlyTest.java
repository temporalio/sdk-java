package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.util.Random;
import org.junit.Rule;
import org.junit.Test;

public class UnwindingReadOnlyTest {
  private static final String STREAM_NAME = "io.temporal.test";
  private static volatile Long draw;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl.class).build();

  @Test
  public void evictionDoesNotDraw() {
    TestWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::run);

    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    testWorkflowRule.invalidateWorkflowCache();
    Long firstEvictionDraw = draw;

    workflow.signal();

    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    testWorkflowRule.invalidateWorkflowCache();
    Long secondEvictionDraw = draw;

    assertEquals(firstEvictionDraw, secondEvictionDraw);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void run();

    @SignalMethod
    void signal();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    @Override
    public void run() {
      Async.procedure(
          () -> {
            Random random = Workflow.getRandomStream(STREAM_NAME);
            try {
              Workflow.await(() -> false);
            } finally {
              if (WorkflowUnsafe.isWorkflowThread() && !WorkflowUnsafe.isReadOnly()) {
                draw = random.nextLong();
              }
            }
          });
      Workflow.await(() -> false);
    }

    @Override
    public void signal() {
      Workflow.getRandomStream(STREAM_NAME).nextLong();
    }
  }
}
