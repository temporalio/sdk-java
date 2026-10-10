package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.cancellationTests.WorkflowAwaitCancelTimerOnConditionTest.TestAwaitWorkflow;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

public class TimerMetadataTest {

  private static final String SLEEP_SUMMARY = "sleep-summary";
  private static final String AWAIT_SUMMARY = "await-summary";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              SleepWithSummaryWorkflowImpl.class,
              AwaitTimeoutWithSummaryWorkflowImpl.class,
              AwaitWithSummaryWorkflowImpl.class)
          .build();

  @Test
  public void sleepSetsTimerSummary() {
    SleepWorkflow workflow = testWorkflowRule.newWorkflowStub(SleepWorkflow.class);
    workflow.execute();

    List<HistoryEvent> timers =
        timerStartedEvents(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId());
    assertEquals(1, timers.size());
    HistoryUtils.assertEventMetadata(timers.get(0), SLEEP_SUMMARY, null);
  }

  @Test
  public void awaitSetsTimerSummaryWhenTimingOut() {
    AwaitTimeoutWorkflow workflow = testWorkflowRule.newWorkflowStub(AwaitTimeoutWorkflow.class);
    assertEquals("timed out", workflow.execute());

    String workflowId = WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId();
    List<HistoryEvent> timers = timerStartedEvents(workflowId);
    assertEquals(1, timers.size());
    HistoryUtils.assertEventMetadata(timers.get(0), AWAIT_SUMMARY, null);
    testWorkflowRule.assertHistoryEvent(workflowId, EventType.EVENT_TYPE_TIMER_FIRED);
  }

  @Test
  public void awaitSetsTimerSummaryWhenConditionIsSatisfied() {
    TestAwaitWorkflow workflow = testWorkflowRule.newWorkflowStub(TestAwaitWorkflow.class);
    String workflowId = WorkflowClient.start(workflow::execute).getWorkflowId();
    testWorkflowRule.waitForTheEndOfWFT(workflowId);
    workflow.unblock();
    assertEquals("condition satisfied", WorkflowStub.fromTyped(workflow).getResult(String.class));

    List<HistoryEvent> timers = timerStartedEvents(workflowId);
    assertEquals(1, timers.size());
    HistoryUtils.assertEventMetadata(timers.get(0), AWAIT_SUMMARY, null);
    testWorkflowRule.assertHistoryEvent(workflowId, EventType.EVENT_TYPE_TIMER_CANCELED);
  }

  /**
   * The history was recorded by an older SDK without CANCEL_AWAIT_TIMER_ON_CONDITION and without a
   * timer summary. Adding a summary must not cause a nondeterminism error on replay.
   */
  @Test
  public void awaitWithSummaryReplaysHistoryRecordedWithoutSummary() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "awaitTimerConditionOldBehavior.json", AwaitWithSummaryWorkflowImpl.class);
  }

  private List<HistoryEvent> timerStartedEvents(String workflowId) {
    return testWorkflowRule.getWorkflowClient().fetchHistory(workflowId).getEvents().stream()
        .filter(HistoryEvent::hasTimerStartedEventAttributes)
        .collect(Collectors.toList());
  }

  @WorkflowInterface
  public interface SleepWorkflow {
    @WorkflowMethod
    void execute();
  }

  @WorkflowInterface
  public interface AwaitTimeoutWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class SleepWithSummaryWorkflowImpl implements SleepWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(
          Duration.ofMillis(100), TimerOptions.newBuilder().setSummary(SLEEP_SUMMARY).build());
    }
  }

  public static class AwaitTimeoutWithSummaryWorkflowImpl implements AwaitTimeoutWorkflow {
    @Override
    public String execute() {
      boolean satisfied =
          Workflow.await(
              Duration.ofMillis(100),
              TimerOptions.newBuilder().setSummary(AWAIT_SUMMARY).build(),
              () -> false);
      return satisfied ? "condition satisfied" : "timed out";
    }
  }

  public static class AwaitWithSummaryWorkflowImpl implements TestAwaitWorkflow {
    private boolean unblocked = false;

    @Override
    public String execute() {
      boolean result =
          Workflow.await(
              Duration.ofHours(1),
              TimerOptions.newBuilder().setSummary(AWAIT_SUMMARY).build(),
              () -> unblocked);
      return result ? "condition satisfied" : "timed out";
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }
}
