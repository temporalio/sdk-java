package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.internal.history.LocalActivityMarkerMetadata;
import io.temporal.internal.history.LocalActivityMarkerUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.ControlledActivityImpl;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * A local activity that retries over a workflow timer must keep its original scheduleToClose
 * deadline when the retry attempt is dispatched by a workflow task that replays the history.
 */
public class LocalActivityScheduleToCloseAcrossReplayTest {

  // Every backoff (4s, coefficient 1) is above the local retry threshold (1s), so every retry goes
  // through a workflow timer. Counting from the first attempt, the 10s budget covers at most two
  // attempts before a retry pre-check or post-failure check gives up with RETRY_STATE_TIMEOUT, well
  // short of MAXIMUM_ATTEMPTS. If the budget restarted on every replay, each check would see a
  // fresh budget and, on a fast run, the activity would run until
  // RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED. A slower
  // worker only spends the budget faster, so it can lower the attempt count but not reach the
  // limit. The test needs one timer-driven retry, so the first attempt must fail within 6s
  // (budget minus backoff); it throws immediately, so it takes milliseconds.
  private static final Duration SCHEDULE_TO_CLOSE = Duration.ofSeconds(10);
  private static final Duration BACKOFF = Duration.ofSeconds(4);
  private static final Duration LOCAL_RETRY_THRESHOLD = Duration.ofSeconds(1);
  private static final int MAXIMUM_ATTEMPTS = 5;

  private static final AtomicInteger replayedWorkflowTasks = new AtomicInteger();

  // Without sticky execution every workflow task carries the full history and the worker replays
  // it from the beginning, so the retry closure always runs on the replay wall clock.
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseTimeskipping(false)
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .setDoNotStart(true)
          .build();

  @Before
  public void setUp() {
    replayedWorkflowTasks.set(0);
  }

  @Test(timeout = 60_000)
  public void scheduleToCloseBudgetSurvivesReplay() {
    ControlledActivityImpl controlledActivity =
        new ControlledActivityImpl(
            Collections.singletonList(ControlledActivityImpl.Outcome.FAIL), MAXIMUM_ATTEMPTS, -1);
    Worker worker = testWorkflowRule.getWorker();
    worker.registerActivitiesImplementations(controlledActivity);
    worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
    testWorkflowRule.getTestEnvironment().start();

    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    WorkflowStub untypedStub = WorkflowStub.fromTyped(workflowStub);
    untypedStub.start(testWorkflowRule.getTaskQueue());
    WorkflowException e =
        assertThrows(WorkflowException.class, () -> untypedStub.getResult(String.class));

    String workflowId = untypedStub.getExecution().getWorkflowId();
    int timersStarted =
        testWorkflowRule.getHistoryEvents(workflowId, EventType.EVENT_TYPE_TIMER_STARTED).size();
    // Each fired timer wakes the workflow up with a new workflow task, which must have replayed.
    assertTrue(
        "Expected at least one backoff timer and a replay for each, got "
            + timersStarted
            + " timers and "
            + replayedWorkflowTasks.get()
            + " replays",
        timersStarted > 0 && replayedWorkflowTasks.get() >= timersStarted);

    // The marker of every attempt records the time the first attempt was scheduled. A retry
    // dispatched after a replay must carry the first attempt's value, not the replay wall clock.
    // This checks the mechanism directly, so it does not depend on how fast attempts run.
    List<Long> firstScheduledTimes = new ArrayList<>();
    for (HistoryEvent event :
        testWorkflowRule.getHistoryEvents(workflowId, EventType.EVENT_TYPE_MARKER_RECORDED)) {
      if (LocalActivityMarkerUtils.hasLocalActivityStructure(event)) {
        LocalActivityMarkerMetadata metadata =
            LocalActivityMarkerUtils.getMetadata(event.getMarkerRecordedEventAttributes());
        assertNotNull("Local activity marker without metadata", metadata);
        firstScheduledTimes.add(metadata.getOriginalScheduledTimestamp());
      }
    }
    assertTrue(
        "Expected a marker for the first attempt and for a retry after replay, got "
            + firstScheduledTimes,
        firstScheduledTimes.size() >= 2);
    for (long firstScheduledTime : firstScheduledTimes) {
      assertEquals(
          "firstSkd of every local activity marker " + firstScheduledTimes,
          firstScheduledTimes.get(0).longValue(),
          firstScheduledTime);
    }

    // With the original baseline the 10s budget runs out before MAXIMUM_ATTEMPTS.
    assertTrue(e.getCause() instanceof ActivityFailure);
    ActivityFailure activityFailure = (ActivityFailure) e.getCause();
    int attempts = controlledActivity.getLastAttempt();
    assertEquals(
        "Retry state after " + attempts + " attempts",
        RetryState.RETRY_STATE_TIMEOUT,
        activityFailure.getRetryState());
    assertTrue(
        "Expected fewer than " + MAXIMUM_ATTEMPTS + " attempts, got " + attempts,
        attempts >= 1 && attempts < MAXIMUM_ATTEMPTS);
    // The retry pre-check wraps the last failure in a scheduleToClose TimeoutFailure. If instead an
    // attempt fails too close to the deadline to back off again, the attempt failure is reported
    // as is.
    Throwable cause = activityFailure.getCause();
    if (cause instanceof TimeoutFailure) {
      assertEquals(
          TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, ((TimeoutFailure) cause).getTimeoutType());
    } else {
      assertTrue("Unexpected cause " + cause, cause instanceof ApplicationFailure);
    }
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      if (WorkflowUnsafe.isReplaying()) {
        replayedWorkflowTasks.incrementAndGet();
      }
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(SCHEDULE_TO_CLOSE)
              .setLocalRetryThreshold(LOCAL_RETRY_THRESHOLD)
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(BACKOFF)
                      .setBackoffCoefficient(1)
                      .setMaximumAttempts(MAXIMUM_ATTEMPTS)
                      .build())
              .build();
      TestActivities.NoArgsReturnsStringActivity activity =
          Workflow.newLocalActivityStub(TestActivities.NoArgsReturnsStringActivity.class, options);
      return activity.execute();
    }
  }
}
