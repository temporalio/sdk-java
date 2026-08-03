package io.temporal.releaseautomation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ReleaseAutomationMainTest {
  @Test
  public void reportsUnrecoveredWorkflowTaskTimeout() {
    assertEquals(
        "workflow-task-failed-or-timed-out",
        ReleaseAutomationMain.unrecoveredWorkflowFailure(
            Arrays.asList(
                event(1, EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED),
                event(2, EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT))));
  }

  @Test
  public void laterWorkflowTaskCompletionRecoversTaskFailure() {
    assertNull(
        ReleaseAutomationMain.unrecoveredWorkflowFailure(
            Arrays.asList(
                event(1, EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED),
                event(2, EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED))));
  }

  @Test
  public void reportsTerminalWorkflowFailure() {
    assertEquals(
        "workflow-execution-failed",
        ReleaseAutomationMain.unrecoveredWorkflowFailure(
            Collections.singletonList(event(3, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED))));
  }

  private static HistoryEvent event(long id, EventType type) {
    return HistoryEvent.newBuilder().setEventId(id).setEventType(type).build();
  }
}
