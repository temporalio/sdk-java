package io.temporal.workflow.cancellationTests;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for the CANCEL_AWAIT_TIMER_ON_CONDITION SDK flag behavior. Tests verify both old and new
 * behavior by explicitly switching the SDK flag, following the Go SDK pattern.
 *
 * <p>Since the flag is NOT auto-enabled (uses checkSdkFlag, not tryUseSdkFlag), tests must
 * explicitly add it to initialFlags to enable the new behavior.
 */
public class WorkflowAwaitCancelTimerOnConditionTest {

  private List<SdkFlag> savedInitialFlags;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestAwaitCancelTimerWorkflowImpl.class,
              TestImmediateConditionWorkflowImpl.class,
              TestReturnValueWorkflowImpl.class)
          .build();

  @Before
  public void setUp() {
    savedInitialFlags = WorkflowStateMachines.initialFlags;
  }

  @After
  public void tearDown() {
    WorkflowStateMachines.initialFlags = savedInitialFlags;
  }

  /**
   * Tests that the timer IS cancelled when the flag is explicitly enabled. With
   * CANCEL_AWAIT_TIMER_ON_CONDITION in initialFlags, we expect TIMER_CANCELED in history.
   */
  @Test
  public void testTimerCancelledWhenFlagEnabled() {
    WorkflowStateMachines.initialFlags =
        Collections.unmodifiableList(
            Arrays.asList(
                SdkFlag.SKIP_YIELD_ON_DEFAULT_VERSION, SdkFlag.CANCEL_AWAIT_TIMER_ON_CONDITION));

    TestAwaitWorkflow workflow = testWorkflowRule.newWorkflowStub(TestAwaitWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    testWorkflowRule.sleep(Duration.ofMillis(500));
    workflow.unblock();

    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    String result = untyped.getResult(String.class);
    assertEquals("condition satisfied", result);

    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_TIMER_STARTED);
    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_TIMER_CANCELED);
  }

  /**
   * Tests that the timer is NOT cancelled when the flag is disabled (default). Without the flag in
   * initialFlags, the old behavior is used: timer runs even after condition is satisfied.
   */
  @Test
  public void testTimerNotCancelledWhenFlagDisabled() {
    // Default initialFlags do NOT include CANCEL_AWAIT_TIMER_ON_CONDITION
    TestAwaitWorkflow workflow = testWorkflowRule.newWorkflowStub(TestAwaitWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    testWorkflowRule.sleep(Duration.ofMillis(500));
    workflow.unblock();

    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    String result = untyped.getResult(String.class);
    assertEquals("condition satisfied", result);

    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_TIMER_STARTED);
    testWorkflowRule.assertNoHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_TIMER_CANCELED);
  }

  /**
   * Tests that no timer is created when the condition is immediately true and the flag is enabled.
   */
  @Test
  public void testNoTimerWhenConditionImmediatelySatisfiedWithFlag() {
    WorkflowStateMachines.initialFlags =
        Collections.unmodifiableList(
            Arrays.asList(
                SdkFlag.SKIP_YIELD_ON_DEFAULT_VERSION, SdkFlag.CANCEL_AWAIT_TIMER_ON_CONDITION));

    TestImmediateConditionWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestImmediateConditionWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    String result = untyped.getResult(String.class);
    assertEquals("immediate condition", result);

    testWorkflowRule.assertNoHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_TIMER_STARTED);
  }

  /**
   * Tests that the await returns true when condition is satisfied and false when it times out. This
   * verifies the return value semantics are preserved with the new flag.
   */
  @Test
  public void testAwaitReturnValue() {
    WorkflowStateMachines.initialFlags =
        Collections.unmodifiableList(
            Arrays.asList(
                SdkFlag.SKIP_YIELD_ON_DEFAULT_VERSION, SdkFlag.CANCEL_AWAIT_TIMER_ON_CONDITION));

    TestReturnValueWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestReturnValueWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    testWorkflowRule.sleep(Duration.ofMillis(500));
    workflow.unblock();

    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    String result = untyped.getResult(String.class);
    assertEquals("conditionSatisfied=true,timedOut=true", result);

    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_TIMER_CANCELED);
    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_TIMER_FIRED);
  }

  /**
   * Tests replay compatibility with old workflow histories that were recorded WITHOUT the
   * CANCEL_AWAIT_TIMER_ON_CONDITION flag.
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
      boolean result = Workflow.await(Duration.ofHours(1), () -> true);
      return result ? "immediate condition" : "unexpected timeout";
    }
  }

  public static class TestReturnValueWorkflowImpl implements TestReturnValueWorkflow {
    private boolean unblocked = false;

    @Override
    public String execute() {
      boolean conditionSatisfied = Workflow.await(Duration.ofHours(1), () -> unblocked);
      boolean timedOut = !Workflow.await(Duration.ofMillis(100), () -> false);
      return "conditionSatisfied=" + conditionSatisfied + ",timedOut=" + timedOut;
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }
}
