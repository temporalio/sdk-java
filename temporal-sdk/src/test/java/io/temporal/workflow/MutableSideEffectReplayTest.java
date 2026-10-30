package io.temporal.workflow;

import static org.junit.Assert.*;

import com.google.common.io.Resources;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowTaskCompletedEventAttributes;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class MutableSideEffectReplayTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(SkippedMutableSideEffectWorkflowImpl.class)
          .build();

  @Test
  public void skippedMutableSideEffectSurvivesFullReplay() throws Exception {
    List<SdkFlag> savedInitialFlags = WorkflowStateMachines.initialFlags;
    List<SdkFlag> enabledFlags = new ArrayList<>(savedInitialFlags);
    enabledFlags.add(SdkFlag.MUTABLE_SIDE_EFFECT_REPLAY_RECOVERY);
    WorkflowStateMachines.initialFlags = Collections.unmodifiableList(enabledFlags);
    try {
      WorkflowReplayer.replayWorkflowExecution(
          runWorkflow(true), SkippedMutableSideEffectWorkflowImpl.class);
    } finally {
      WorkflowStateMachines.initialFlags = savedInitialFlags;
    }
  }

  @Test
  public void replayHistoryWrittenWithoutEviction() throws Exception {
    assertFalse(
        "The recovery flag must remain disabled by default in its first release",
        WorkflowStateMachines.initialFlags.contains(SdkFlag.MUTABLE_SIDE_EFFECT_REPLAY_RECOVERY));
    WorkflowExecutionHistory history = runWorkflow(false);
    WorkflowReplayer.replayWorkflowExecution(history, SkippedMutableSideEffectWorkflowImpl.class);
    testWorkflowRule.regenerateHistoryForReplay(
        history.getWorkflowExecution().getWorkflowId(), "mutableSideEffectCommandlessTasks");
  }

  @Test
  public void replayHistoryWrittenBeforeFix() throws Exception {
    // Captured on the unmodified SDK, with both commandless tasks executed from the sticky cache.
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "mutableSideEffectCommandlessTasks.json", SkippedMutableSideEffectWorkflowImpl.class);
  }

  @Test
  public void replayPreservesClockAfterSkippedMutableSideEffect() throws Exception {
    // This test-server history has controlled, monotonic task timestamps to distinguish callback
    // timing without wall-clock waits.
    // The recorded execution resumes the skipped call at base + 1000. Legacy replay resumes it
    // at base + 3000, while unconditional task-start cleanup resumes it at base + 2000.
    // Both the recorded execution and legacy replay emit two timers; the intermediate time
    // changes the branch and omits one timer. No skip-count matching is involved.
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "mutableSideEffectCallbackTime.json", ClockSensitiveWorkflowImpl.class);
  }

  @Test
  public void replayUsesOriginalClockWhenRecoveryFlagIsAlreadyPresent() throws Exception {
    // With the flag present from the start, the skipped continuation must run at its original
    // task time (+1000), not the next task's time (+2000).
    WorkflowReplayer.replayWorkflowExecution(
        new WorkflowExecutionHistory(clockHistoryWithRecoveryFlag(4).build()),
        ClockSensitiveWorkflowImpl.class);
  }

  @Test
  public void laterFlagDoesNotRetimeEarlierSkippedCall() throws Exception {
    // The second signal makes an unchanged call, but the earlier skipped continuation has already
    // run on the cached writer. Recording the flag on that signal's task must not resume the
    // earlier invocation at +2000 during replay; its legacy replay time is +3000.
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "mutableSideEffectCallbackTime.json", LateOptInWorkflowImpl.class);
    LateOptInWorkflowImpl.lateCallTime = Long.MIN_VALUE;
    WorkflowReplayer.replayWorkflowExecution(
        new WorkflowExecutionHistory(clockHistoryWithRecoveryFlag(16).build()),
        LateOptInWorkflowImpl.class);
    assertEquals(
        ClockSensitiveWorkflowImpl.INTERMEDIATE_TASK_TIME, LateOptInWorkflowImpl.lateCallTime);
  }

  @Test
  public void recoveryDoesNotHideAnExtraTimerCommand() throws Exception {
    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                WorkflowReplayer.replayWorkflowExecution(
                    new WorkflowExecutionHistory(clockHistoryWithRecoveryFlag(4).build()),
                    ExtraTimerWorkflowImpl.class));
    assertTrue(error.getMessage(), error.getMessage().contains("[TMPRL1100]"));
  }

  @Test
  public void recoveryDoesNotIgnoreARecordedMutableSideEffectMarker() throws Exception {
    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                WorkflowReplayer.replayWorkflowExecution(
                    new WorkflowExecutionHistory(clockHistoryWithRecoveryFlag(4).build()),
                    NoMutableSideEffectWorkflowImpl.class));
    assertTrue(error.getMessage(), error.getMessage().contains("[TMPRL1100]"));
  }

  private History.Builder clockHistoryWithRecoveryFlag(int completedEventId) throws Exception {
    WorkflowExecutionHistory history =
        WorkflowExecutionHistory.fromJson(
            Resources.toString(
                Resources.getResource("mutableSideEffectCallbackTime.json"),
                StandardCharsets.UTF_8));
    History.Builder flagged = history.getHistory().toBuilder();
    int completedIndex = completedEventId - 1;
    HistoryEvent completed = flagged.getEvents(completedIndex);
    assertEquals(EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED, completed.getEventType());
    WorkflowTaskCompletedEventAttributes attributes =
        completed.getWorkflowTaskCompletedEventAttributes();
    flagged.setEvents(
        completedIndex,
        completed.toBuilder()
            .setWorkflowTaskCompletedEventAttributes(
                attributes.toBuilder()
                    .setSdkMetadata(
                        attributes.getSdkMetadata().toBuilder()
                            .addLangUsedFlags(
                                SdkFlag.MUTABLE_SIDE_EFFECT_REPLAY_RECOVERY.getValue()))));
    return flagged;
  }

  private WorkflowExecutionHistory runWorkflow(boolean evict) throws Exception {
    SkippedMutableSideEffectWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(SkippedMutableSideEffectWorkflow.class);
    WorkflowClient.start(workflow::run);
    assertEquals("1", workflow.getValues());

    workflow.setValue(1);
    // A strongly consistent query waits for the preceding signal's task to complete, without
    // adding a command event that could consume the skipped call's fake marker during replay.
    assertEquals("1,1", workflow.getValues());
    if (evict) {
      testWorkflowRule.invalidateWorkflowCache();
    }

    workflow.setValue(2);
    assertEquals("1,1,2", workflow.getValues());

    workflow.setValue(2);
    assertEquals("1,1,2,2", workflow.getValues());
    if (evict) {
      testWorkflowRule.invalidateWorkflowCache();
    }

    workflow.setValue(3);
    assertEquals(
        "1,1,2,2", WorkflowStub.fromTyped(workflow).getResult(10, TimeUnit.SECONDS, String.class));

    WorkflowExecutionHistory history =
        testWorkflowRule.getExecutionHistory(
            WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId());
    assertEquals(
        2,
        history.getEvents().stream()
            .filter(event -> event.hasMarkerRecordedEventAttributes())
            .count());
    assertEquals(
        "The recovery flag must only be recorded after explicit test opt-in",
        evict,
        history.getEvents().stream()
            .filter(HistoryEvent::hasWorkflowTaskCompletedEventAttributes)
            .anyMatch(
                event ->
                    event
                        .getWorkflowTaskCompletedEventAttributes()
                        .getSdkMetadata()
                        .getLangUsedFlagsList()
                        .contains(SdkFlag.MUTABLE_SIDE_EFFECT_REPLAY_RECOVERY.getValue())));
    SDKTestWorkflowRule.assertNoHistoryEvent(
        history.getHistory(), EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
    SDKTestWorkflowRule.assertNoHistoryEvent(
        history.getHistory(), EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT);
    return history;
  }

  @WorkflowInterface
  public interface SkippedMutableSideEffectWorkflow {
    @WorkflowMethod
    String run();

    @SignalMethod
    void setValue(int value);

    @QueryMethod
    String getValues();
  }

  public static class SkippedMutableSideEffectWorkflowImpl
      implements SkippedMutableSideEffectWorkflow {
    private int value = 1;
    private int signals;
    private final StringBuilder result = new StringBuilder();

    @Override
    public String run() {
      for (int i = 0; i < 4; i++) {
        int current =
            Workflow.mutableSideEffect("value", Integer.class, (a, b) -> !a.equals(b), () -> value);
        if (i > 0) {
          result.append(",");
        }
        result.append(current);
        int iteration = i;
        Workflow.await(() -> signals > iteration);
      }
      return result.toString();
    }

    @Override
    public void setValue(int value) {
      this.value = value;
      signals++;
    }

    @Override
    public String getValues() {
      return result.toString();
    }
  }

  @WorkflowInterface
  public interface ClockSensitiveWorkflow {
    @WorkflowMethod(name = "Probe")
    String run();

    @SignalMethod
    void poke();
  }

  public static class ClockSensitiveWorkflowImpl implements ClockSensitiveWorkflow {
    private static final long INTERMEDIATE_TASK_TIME = 1800000002000L;
    private int signals;

    @Override
    public String run() {
      Workflow.mutableSideEffect("key", Integer.class, (a, b) -> !a.equals(b), () -> 1);
      Workflow.await(() -> signals >= 1);
      Workflow.mutableSideEffect("key", Integer.class, (a, b) -> !a.equals(b), () -> 1);
      long observed = Workflow.currentTimeMillis();
      Workflow.await(() -> signals >= 3);
      if (observed != INTERMEDIATE_TASK_TIME) {
        Workflow.newTimer(Duration.ofSeconds(10));
      }
      return "done";
    }

    @Override
    public void poke() {
      signals++;
      if (signals == 3) {
        Workflow.newTimer(Duration.ofSeconds(30));
      }
    }
  }

  public static class ExtraTimerWorkflowImpl extends ClockSensitiveWorkflowImpl {
    @Override
    public String run() {
      String result = super.run();
      Workflow.newTimer(Duration.ofSeconds(10));
      return result;
    }
  }

  public static class LateOptInWorkflowImpl extends ClockSensitiveWorkflowImpl {
    private static volatile long lateCallTime;
    private int pokes;

    @Override
    public void poke() {
      super.poke();
      if (++pokes == 2) {
        Workflow.mutableSideEffect("key", Integer.class, (a, b) -> !a.equals(b), () -> 1);
        lateCallTime = Workflow.currentTimeMillis();
      }
    }
  }

  public static class NoMutableSideEffectWorkflowImpl implements ClockSensitiveWorkflow {
    @Override
    public String run() {
      return "done";
    }

    @Override
    public void poke() {}
  }
}
