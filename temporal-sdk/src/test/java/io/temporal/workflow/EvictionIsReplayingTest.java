package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Rule;
import org.junit.Test;

public class EvictionIsReplayingTest {
  private static final List<Boolean> replayingInFinally = new CopyOnWriteArrayList<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl.class).build();

  @Test
  public void evictionUnwindsWorkflowCodeAsReplay() {
    TestWorkflow workflow = testWorkflowRule.newWorkflowStub(TestWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    testWorkflowRule.invalidateWorkflowCache();

    workflow.finish();
    WorkflowStub.fromTyped(workflow).getResult(Void.class);

    // The eviction unwinds the finally block as replay; the completion runs it live.
    assertEquals(Arrays.asList(true, false), replayingInFinally);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void execute();

    @SignalMethod
    void finish();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    private boolean finished;

    @Override
    public void execute() {
      try {
        Workflow.await(() -> finished);
      } finally {
        replayingInFinally.add(WorkflowUnsafe.isReplaying());
      }
    }

    @Override
    public void finish() {
      finished = true;
    }
  }
}
