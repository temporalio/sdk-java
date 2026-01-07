package io.temporal.workflow.cancellationTests;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for the CANCEL_AWAIT_TIMER_ON_CONDITION SDK flag behavior. This flag ensures that
 * Workflow.await(duration, condition) cancels the timer when the condition is satisfied before the
 * timeout.
 *
 * <p>Note: These tests verify the NEW behavior (timer cancellation) for new workflows. Replay
 * compatibility for old workflows (without the flag) is preserved by the SDK flag mechanism - the
 * old behavior is only used during replay of histories that were recorded without the flag.
 */
public class WorkflowAwaitCancelTimerOnConditionTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestAwaitCancelTimerWorkflowImpl.class,
              TestImmediateConditionWorkflowImpl.class,
              TestReturnValueWorkflowImpl.class)
          .build();

  /**
   * Tests that the timer is cancelled when the await condition is satisfied before the timeout.
   * With the CANCEL_AWAIT_TIMER_ON_CONDITION flag enabled, we expect to see a TIMER_CANCELED event
   * in the history when a signal satisfies the condition.
   */
  @Test
  public void testTimerCancelledWhenConditionSatisfied() {
    TestAwaitWorkflow workflow = testWorkflowRule.newWorkflowStub(TestAwaitWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    // Wait a bit for workflow to start and begin awaiting
    testWorkflowRule.sleep(Duration.ofMillis(500));

    // Signal to satisfy the condition
    workflow.unblock();

    // Get the result
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    String result = untyped.getResult(String.class);
    assertEquals("condition satisfied", result);

    // Verify timer was started and then cancelled
    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_TIMER_STARTED);
    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_TIMER_CANCELED);
  }

  /**
   * Tests that no timer is created when the condition is already satisfied at the time of the await
   * call. With the CANCEL_AWAIT_TIMER_ON_CONDITION flag enabled, if the condition is immediately
   * true, we skip creating a timer entirely.
   */
  @Test
  public void testNoTimerWhenConditionImmediatelySatisfied() {
    TestImmediateConditionWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestImmediateConditionWorkflow.class);

    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    String result = untyped.getResult(String.class);
    assertEquals("immediate condition", result);

    // Verify no timer was created
    testWorkflowRule.assertNoHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_TIMER_STARTED);
  }

  /**
   * Tests that the await returns true when condition is satisfied and false when it times out. This
   * verifies the return value semantics are preserved with the new flag, and that only the first
   * timer (condition satisfied) is canceled while the second timer (timeout) fires normally.
   */
  @Test
  public void testAwaitReturnValue() {
    TestReturnValueWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestReturnValueWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    // Signal to satisfy the first condition
    testWorkflowRule.sleep(Duration.ofMillis(500));
    workflow.unblock();

    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    String result = untyped.getResult(String.class);

    // First await should return true (condition satisfied), second should return false (timeout)
    // timedOut = !await(...) = !false = true when it times out
    assertEquals("conditionSatisfied=true,timedOut=true", result);

    // Verify: first timer was canceled (condition satisfied), second timer fired (timeout)
    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_TIMER_CANCELED);
    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_TIMER_FIRED);
  }

  /**
   * Tests replay compatibility with old workflow histories that were recorded WITHOUT the
   * CANCEL_AWAIT_TIMER_ON_CONDITION flag. This ensures the SDK can correctly replay workflows that
   * used the old behavior (timer not cancelled when condition satisfied).
   *
   * <p>The history file (awaitTimerConditionOldBehavior.json) contains a workflow execution that:
   * 1. Started with Workflow.await(1 hour, condition) 2. Received a signal that satisfied the
   * condition 3. Completed without canceling the timer (old behavior)
   *
   * <p>The replay should succeed without throwing a non-determinism error, proving that the new SDK
   * code correctly falls back to old behavior when replaying histories without the flag.
   */
  @Test
  public void testReplayOldHistoryWithoutFlag() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "awaitTimerConditionOldBehavior.json", TestAwaitCancelTimerWorkflowImpl.class);
  }

  @WorkflowInterface
  public interface TestAwaitWorkflow {
    @WorkflowMethod
    String execute();

    @SignalMethod
    void unblock();
  }

  @WorkflowInterface
  public interface TestImmediateConditionWorkflow {
    @WorkflowMethod
    String execute();
  }

  @WorkflowInterface
  public interface TestReturnValueWorkflow {
    @WorkflowMethod
    String execute();

    @SignalMethod
    void unblock();
  }

  public static class TestAwaitCancelTimerWorkflowImpl implements TestAwaitWorkflow {
    private boolean unblocked = false;

    @Override
    public String execute() {
      boolean result = Workflow.await(Duration.ofHours(1), () -> unblocked);
      return result ? "condition satisfied" : "timed out";
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }

  public static class TestImmediateConditionWorkflowImpl implements TestImmediateConditionWorkflow {
    @Override
    public String execute() {
      // Condition is immediately true
      boolean result = Workflow.await(Duration.ofHours(1), () -> true);
      return result ? "immediate condition" : "unexpected timeout";
    }
  }

  public static class TestReturnValueWorkflowImpl implements TestReturnValueWorkflow {
    private boolean unblocked = false;

    @Override
    public String execute() {
      // First await: condition will be satisfied by signal
      boolean conditionSatisfied = Workflow.await(Duration.ofHours(1), () -> unblocked);

      // Second await: condition will never be satisfied, should timeout
      boolean timedOut = !Workflow.await(Duration.ofMillis(100), () -> false);

      return "conditionSatisfied=" + conditionSatisfied + ",timedOut=" + timedOut;
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }
}
