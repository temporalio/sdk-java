package io.temporal.workflow.signalTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.testing.WorkflowHistoryLoader;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class BufferedSignalReplayTest {
  private final List<SdkFlag> savedInitialFlags = WorkflowStateMachines.initialFlags;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(BufferedSignalDeliveryTest.TestWorkflowImpl.class)
          .build();

  @After
  public void restoreFlags() {
    WorkflowStateMachines.initialFlags = savedInitialFlags;
  }

  @Test
  public void replayRecordedLegacyHistories() throws Exception {
    // These histories were recorded on 77a3e023 before the fix. Even a worker that enables the
    // flag for new workflows must preserve their duplicate handler commands on replay.
    List<SdkFlag> flags = new ArrayList<>(savedInitialFlags);
    flags.add(SdkFlag.CONSUME_BUFFERED_SIGNALS);
    WorkflowStateMachines.initialFlags = Collections.unmodifiableList(flags);
    replayRecordedHistories(true, false);
  }

  @Test
  public void replayRecordedFlaggedHistories() throws Exception {
    // The flag recorded in history selects the fix even though new workflows don't enable it yet.
    assertFalse(WorkflowStateMachines.initialFlags.contains(SdkFlag.CONSUME_BUFFERED_SIGNALS));
    replayRecordedHistories(false, false);
  }

  @Test
  public void addingFlagToLegacyHistoriesChangesCommands() throws Exception {
    replayRecordedHistories(true, true);
  }

  @Test
  public void removingFlagFromFixedHistoriesChangesCommands() throws Exception {
    replayRecordedHistories(false, true);
  }

  private void replayRecordedHistories(boolean legacy, boolean changeFlag) throws Exception {
    for (String scenario : new String[] {"named", "dynamic", "unmatched"}) {
      WorkflowExecutionHistory recorded =
          WorkflowHistoryLoader.readHistoryFromResource(
              "bufferedSignal-" + scenario + (legacy ? "-legacy" : "-fixed") + ".json");
      boolean hasFlag =
          recorded.getEvents().stream()
              .anyMatch(
                  event ->
                      event
                          .getWorkflowTaskCompletedEventAttributes()
                          .getSdkMetadata()
                          .getLangUsedFlagsList()
                          .contains(SdkFlag.CONSUME_BUFFERED_SIGNALS.getValue()));
      assertEquals(!legacy, hasFlag);
      if (!changeFlag) {
        WorkflowReplayer.replayWorkflowExecution(recorded, testWorkflowRule.getWorker());
      } else {
        WorkflowExecutionHistory changed = toggleFlag(recorded, legacy);
        RuntimeException failure =
            assertThrows(
                RuntimeException.class,
                () ->
                    WorkflowReplayer.replayWorkflowExecution(
                        changed, testWorkflowRule.getWorker()));
        // Query replay serializes the underlying failure into the exception message.
        assertTrue(
            failure.getMessage(), failure.getMessage().contains("NonDeterministicException"));
        assertTrue(
            failure.getMessage(), failure.getMessage().contains("does not match command type"));
      }
    }
  }

  private static WorkflowExecutionHistory toggleFlag(
      WorkflowExecutionHistory recorded, boolean enabled) {
    History.Builder history = recorded.getHistory().toBuilder();
    for (HistoryEvent.Builder event : history.getEventsBuilderList()) {
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
        List<Integer> flags =
            new ArrayList<>(
                event
                    .getWorkflowTaskCompletedEventAttributes()
                    .getSdkMetadata()
                    .getLangUsedFlagsList());
        flags.remove(Integer.valueOf(SdkFlag.CONSUME_BUFFERED_SIGNALS.getValue()));
        if (enabled) {
          flags.add(SdkFlag.CONSUME_BUFFERED_SIGNALS.getValue());
        }
        event
            .getWorkflowTaskCompletedEventAttributesBuilder()
            .getSdkMetadataBuilder()
            .clearLangUsedFlags()
            .addAllLangUsedFlags(flags);
      }
    }
    return new WorkflowExecutionHistory(
        history.build(), recorded.getWorkflowExecution().getWorkflowId());
  }
}
