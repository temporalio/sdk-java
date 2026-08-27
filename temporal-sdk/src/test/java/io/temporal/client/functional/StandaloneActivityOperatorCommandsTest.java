package io.temporal.client.functional;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.api.enums.v1.PendingActivityState;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityHandle;
import io.temporal.client.DescribeActivityOptions;
import io.temporal.client.PauseActivityOptions;
import io.temporal.client.ResetActivityOptions;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.UpdateActivityOptions;
import io.temporal.common.CancellationToken;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor.*;
import io.temporal.common.interceptors.ActivityClientCallsInterceptorBase;
import io.temporal.common.interceptors.ActivityClientInterceptorBase;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

/**
 * Integration tests for the standalone-activity operator commands on {@link ActivityHandle}: pause,
 * unpause, reset and updateOptions. Each asserts an observable server state change.
 *
 * <p>Gated behind {@link SDKTestWorkflowRule#useExternalService} because the embedded test server
 * does not support the standalone activity APIs.
 */
public class StandaloneActivityOperatorCommandsTest {

  /** Heartbeat details are opt-in on describe; these tests assert on them. */
  private static final DescribeActivityOptions WITH_HEARTBEAT_DETAILS =
      DescribeActivityOptions.newBuilder().setIncludeHeartbeatDetails(true).build();

  // ---------------------------------------------------------------------------
  // Activities
  // ---------------------------------------------------------------------------

  /** Long-running activity that heartbeats and runs until cancellation/interruption. */
  @ActivityInterface
  public interface SlowActivity {
    @ActivityMethod(name = "Slow")
    void run();
  }

  public static class SlowActivityImpl implements SlowActivity {
    @Override
    public void run() {
      Activity.getExecutionContext().heartbeat(null);
      while (true) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        Activity.getExecutionContext().heartbeat(null);
      }
    }
  }

  /** Takes two arguments, so a describe can read a multi-argument input back off the server. */
  @ActivityInterface
  public interface TwoArgActivity {
    @ActivityMethod(name = "TwoArg")
    String run(String word, Integer count);
  }

  public static class TwoArgActivityImpl implements TwoArgActivity {
    @Override
    public String run(String word, Integer count) {
      return word + "-" + count;
    }
  }

  /** Returns immediately. Used with a start delay so it can be paused while scheduled. */
  @ActivityInterface
  public interface QuickActivity {
    @ActivityMethod(name = "Quick")
    String run();
  }

  public static class QuickActivityImpl implements QuickActivity {
    @Override
    public String run() {
      return "resumed";
    }
  }

  /** Fails until the third attempt, then succeeds. Drives an activity past its first attempt. */
  @ActivityInterface
  public interface FailThenSucceedActivity {
    @ActivityMethod(name = "FailThenSucceed")
    String run();
  }

  public static class FailThenSucceedActivityImpl implements FailThenSucceedActivity {
    @Override
    public String run() {
      if (Activity.getExecutionContext().getInfo().getAttempt() < 3) {
        throw ApplicationFailure.newFailure("retryable failure", "retry-type");
      }
      return "done";
    }
  }

  /** Heartbeats, fails the first attempt, then succeeds. */
  @ActivityInterface
  public interface HeartbeatFailIncrementActivity {
    @ActivityMethod(name = "HeartbeatFailIncrement")
    Integer run(Integer value);
  }

  public static class HeartbeatFailIncrementActivityImpl implements HeartbeatFailIncrementActivity {
    @Override
    public Integer run(Integer value) {
      Activity.getExecutionContext().heartbeat("heartbeat details");
      if (Activity.getExecutionContext().getInfo().getAttempt() == 1) {
        throw ApplicationFailure.newFailure("deliberate first-attempt failure", "first-attempt");
      }
      return value + 1;
    }
  }

  /**
   * Records heartbeat details on attempt 1, then blocks waiting for cancellation. The heartbeat
   * runs on its own — not adjacent to any completion RPC — so the details reliably persist and are
   * observable via describe. Later attempts (after a reset or an unpause that spawns a new attempt)
   * do not heartbeat, so any operator-driven clearing of the details stays observable.
   */
  @ActivityInterface
  public interface HeartbeatOnceActivity {
    @ActivityMethod(name = "HeartbeatOnce")
    void run();
  }

  public static class HeartbeatOnceActivityImpl implements HeartbeatOnceActivity {
    @Override
    public void run() {
      ActivityExecutionContext ctx = Activity.getExecutionContext();
      if (ctx.getInfo().getAttempt() == 1) {
        ctx.heartbeat("hb-details");
      }
      CancellationToken<ActivityCanceledException> token = ctx.getCancellationToken();
      while (!token.isCancellationRequested()) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Rule + helpers
  // ---------------------------------------------------------------------------

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setActivityImplementations(
              new SlowActivityImpl(),
              new QuickActivityImpl(),
              new FailThenSucceedActivityImpl(),
              new TwoArgActivityImpl(),
              new HeartbeatOnceActivityImpl(),
              new HeartbeatFailIncrementActivityImpl())
          .build();

  /**
   * A running activity does not transition straight to PAUSED on pause: the server records
   * PAUSE_REQUESTED and only moves to PAUSED once the worker drops the attempt. A long-running
   * heartbeating activity that has not yet noticed the pause stays in PAUSE_REQUESTED, so both
   * states count as "paused" for an observability assertion.
   */
  private static final List<PendingActivityState> PAUSED_STATES =
      Arrays.asList(
          PendingActivityState.PENDING_ACTIVITY_STATE_PAUSED,
          PendingActivityState.PENDING_ACTIVITY_STATE_PAUSE_REQUESTED);

  private String uniqueId() {
    return "act-" + UUID.randomUUID();
  }

  private ActivityClient newActivityClient() {
    return ActivityClient.newInstance(
        testWorkflowRule.getWorkflowServiceStubs(),
        ActivityClientOptions.newBuilder().setNamespace(SDKTestWorkflowRule.NAMESPACE).build());
  }

  private void assertEventuallyPaused(ActivityHandle<?> handle) {
    assertEventually(
        Duration.ofSeconds(30),
        () ->
            assertTrue(
                "expected paused run state, got " + handle.describe().getRunState(),
                PAUSED_STATES.contains(handle.describe().getRunState())));
  }

  /** Start a SlowActivity and wait until it has actually started running on the worker. */
  private ActivityHandle<Void> startRunningSlowActivity(StartActivityOptions.Builder optsBuilder) {
    ActivityHandle<Void> handle =
        newActivityClient().start(SlowActivity.class, SlowActivity::run, optsBuilder.build());
    assertEventually(
        Duration.ofSeconds(30),
        () ->
            assertEquals(
                PendingActivityState.PENDING_ACTIVITY_STATE_STARTED,
                handle.describe().getRunState()));
    return handle;
  }

  /**
   * Start a HeartbeatOnceActivity and wait until its first attempt has recorded heartbeat details.
   * The activity keeps running (sleeping until interrupted) once heartbeat has fired, so pause
   * transitions the activity through PAUSE_REQUESTED to PAUSED — assertEventuallyPaused tolerates
   * both.
   */
  private ActivityHandle<Void> startHeartbeatReadyActivity() {
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId(uniqueId())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .setHeartbeatTimeout(Duration.ofSeconds(30))
            .build();
    ActivityHandle<Void> handle =
        newActivityClient().start(HeartbeatOnceActivity.class, HeartbeatOnceActivity::run, opts);
    assertEventually(
        Duration.ofSeconds(30),
        () ->
            assertTrue(
                "expected heartbeat details to be recorded",
                handle.describe(WITH_HEARTBEAT_DETAILS).hasHeartbeatDetails()));
    return handle;
  }

  private StartActivityOptions.Builder slowOpts() {
    return StartActivityOptions.newBuilder()
        .setId(uniqueId())
        .setTaskQueue(testWorkflowRule.getTaskQueue())
        .setStartToCloseTimeout(Duration.ofSeconds(60))
        .setHeartbeatTimeout(Duration.ofSeconds(30));
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  // Overrides the rule's default 10s global timeout: the start delay makes this take longer.
  @Test(timeout = 60_000)
  public void unpauseResumes() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityClient client = newActivityClient();
    // Start with a long delay so the activity sits SCHEDULED and can be paused before it runs.
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId(uniqueId())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .setStartDelay(Duration.ofSeconds(30))
            .build();
    ActivityHandle<String> handle = client.start(QuickActivity.class, QuickActivity::run, opts);

    handle.pause(PauseActivityOptions.newBuilder().setReason("pause-before-unpause").build());
    // A not-yet-started (scheduled) activity transitions fully to PAUSED.
    assertEventually(
        Duration.ofSeconds(30),
        () ->
            assertEquals(
                PendingActivityState.PENDING_ACTIVITY_STATE_PAUSED,
                handle.describe().getRunState()));

    handle.unpause();
    // After unpause the activity proceeds and completes successfully (proving it resumed).
    assertEquals("resumed", handle.getResult());
  }

  // Overrides the rule's default 10s global timeout: driving retries + reset takes longer.
  @Test(timeout = 60_000)
  public void reset() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityClient client = newActivityClient();
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId(uniqueId())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofMillis(200))
                    .setBackoffCoefficient(1.0)
                    .setMaximumInterval(Duration.ofMillis(200))
                    .setMaximumAttempts(50)
                    .build())
            .build();
    ActivityHandle<String> handle =
        client.start(FailThenSucceedActivity.class, FailThenSucceedActivity::run, opts);

    // Wait until the activity has recorded more than one attempt (i.e. it has retried).
    assertEventually(
        Duration.ofSeconds(30),
        () -> assertTrue("expected attempt > 1 before reset", handle.describe().getAttempt() > 1));

    handle.reset();

    // After reset the attempt counter goes back to the start.
    assertEventually(
        Duration.ofSeconds(30),
        () -> assertEquals("attempt should be reset to 1", 1, handle.describe().getAttempt()));
    handle.terminate("cleanup");
  }

  @Test
  public void updateOptionsRespectsMask() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<Void> handle =
        startRunningSlowActivity(
            slowOpts()
                .setStartToCloseTimeout(Duration.ofSeconds(45))
                .setScheduleToCloseTimeout(Duration.ofSeconds(120)));

    UpdateActivityOptions updated =
        handle.updateOptions(
            UpdateActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(90))
                .build());

    // Returned options: only start_to_close changed; schedule_to_close kept its original value.
    assertEquals(Duration.ofSeconds(90), updated.getStartToCloseTimeout());
    assertEquals(Duration.ofSeconds(120), updated.getScheduleToCloseTimeout());

    // Confirm via describe that the partial update was applied server-side.
    assertEventually(
        Duration.ofSeconds(30),
        () -> {
          ActivityExecutionDescription desc = handle.describe();
          assertEquals(Duration.ofSeconds(90), desc.getStartToCloseTimeout());
          assertEquals(Duration.ofSeconds(120), desc.getScheduleToCloseTimeout());
        });
    handle.terminate("cleanup");
  }

  // Overrides the rule's default 10s global timeout: uses a start delay to keep the activity
  // scheduled while every option is updated and observed.
  @Test(timeout = 60_000)
  public void updateOptionsAllFields() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    // Start delayed so the activity stays SCHEDULED (never runs) while we update every option.
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId(uniqueId())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setScheduleToCloseTimeout(Duration.ofSeconds(100))
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setStartDelay(Duration.ofSeconds(300))
            .build();
    ActivityHandle<String> handle =
        newActivityClient().start(QuickActivity.class, QuickActivity::run, opts);

    UpdateActivityOptions updated =
        handle.updateOptions(
            UpdateActivityOptions.newBuilder()
                .setTaskQueue("updated-tq")
                .setScheduleToCloseTimeout(Duration.ofSeconds(200))
                .setScheduleToStartTimeout(Duration.ofSeconds(15))
                .setStartToCloseTimeout(Duration.ofSeconds(90))
                .setHeartbeatTimeout(Duration.ofSeconds(25))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setBackoffCoefficient(2.0)
                        .setMaximumAttempts(7)
                        .build())
                .setPriority(Priority.newBuilder().setPriorityKey(3).build())
                .setStartDelay(Duration.ofSeconds(500))
                .build());

    // Every field is settable and lands: the returned options reflect each new value.
    assertEquals("updated-tq", updated.getTaskQueue());
    assertEquals(Duration.ofSeconds(200), updated.getScheduleToCloseTimeout());
    assertEquals(Duration.ofSeconds(15), updated.getScheduleToStartTimeout());
    assertEquals(Duration.ofSeconds(90), updated.getStartToCloseTimeout());
    assertEquals(Duration.ofSeconds(25), updated.getHeartbeatTimeout());
    assertEquals(7, updated.getRetryOptions().getMaximumAttempts());
    assertEquals(3, updated.getPriority().getPriorityKey());
    assertEquals(Duration.ofSeconds(500), updated.getStartDelay());

    // And describe reflects them server-side.
    ActivityExecutionDescription desc = handle.describe();
    assertEquals("updated-tq", desc.getTaskQueue());
    assertEquals(Duration.ofSeconds(200), desc.getScheduleToCloseTimeout());
    assertEquals(Duration.ofSeconds(15), desc.getScheduleToStartTimeout());
    assertEquals(Duration.ofSeconds(90), desc.getStartToCloseTimeout());
    assertEquals(Duration.ofSeconds(25), desc.getHeartbeatTimeout());
    assertEquals(7, desc.getRetryOptions().getMaximumAttempts());
    assertEquals(3, desc.getPriority().getPriorityKey());
    assertEquals(Duration.ofSeconds(500), desc.getStartDelay());
    // execution_time (api#807 + temporal#11017): reflects the updated start_delay. Server
    // recomputes it on UpdateActivityOptions, so it lands at schedule_time + 500s (the new value),
    // not schedule_time + 300s (the value at start).
    assertEquals(
        desc.getScheduledTime().plus(Duration.ofSeconds(500)).getEpochSecond(),
        desc.getExecutionTime().getEpochSecond());

    handle.terminate("cleanup");
  }

  @Test
  public void updateOptionsRestoreOriginal() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<Void> handle =
        startRunningSlowActivity(slowOpts().setStartToCloseTimeout(Duration.ofSeconds(45)));

    // Change an option away from the original.
    UpdateActivityOptions changed =
        handle.updateOptions(
            UpdateActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(90))
                .build());
    assertEquals(Duration.ofSeconds(90), changed.getStartToCloseTimeout());

    // restore_original alone reverts to the value the activity was created with.
    UpdateActivityOptions restored = handle.restoreOriginalOptions();
    assertEquals(Duration.ofSeconds(45), restored.getStartToCloseTimeout());
    handle.terminate("cleanup");
  }

  @Test(timeout = 60_000)
  public void updateOptionsOnPausedActivity() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    // Start delayed so the activity sits SCHEDULED and pauses to a true PAUSED state rather than
    // the PAUSE_REQUESTED a running activity lands in.
    ActivityHandle<String> handle =
        newActivityClient()
            .start(
                QuickActivity.class,
                QuickActivity::run,
                StartActivityOptions.newBuilder()
                    .setId(uniqueId())
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setStartToCloseTimeout(Duration.ofSeconds(45))
                    .setScheduleToCloseTimeout(Duration.ofSeconds(120))
                    .setStartDelay(Duration.ofSeconds(60))
                    .build());
    handle.pause(PauseActivityOptions.newBuilder().setReason("hold").build());
    assertEventually(
        Duration.ofSeconds(30),
        () ->
            assertEquals(
                PendingActivityState.PENDING_ACTIVITY_STATE_PAUSED,
                handle.describe().getRunState()));

    // Updating options is legal while paused, and the new value lands.
    UpdateActivityOptions updated =
        handle.updateOptions(
            UpdateActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(90))
                .build());
    assertEquals(Duration.ofSeconds(90), updated.getStartToCloseTimeout());

    ActivityExecutionDescription desc = handle.describe();
    assertEquals(Duration.ofSeconds(90), desc.getStartToCloseTimeout());
    // The mask is still honored while paused — an option we didn't touch keeps its original value.
    assertEquals(Duration.ofSeconds(120), desc.getScheduleToCloseTimeout());
    // And the update leaves the activity paused; it is not an implicit unpause.
    assertEquals(PendingActivityState.PENDING_ACTIVITY_STATE_PAUSED, desc.getRunState());
    assertEquals(ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_PAUSED, desc.getStatus());

    handle.terminate("cleanup");
  }

  @Test(timeout = 60_000)
  public void resetKeepsPaused() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    // Start delayed so the activity sits SCHEDULED and pauses to a true PAUSED state (not the
    // PAUSE_REQUESTED of a running activity), which is what keep_paused must preserve across reset.
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId(uniqueId())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .setStartDelay(Duration.ofSeconds(30))
            .build();
    ActivityHandle<String> handle =
        newActivityClient().start(QuickActivity.class, QuickActivity::run, opts);

    handle.pause(PauseActivityOptions.newBuilder().setReason("hold").build());
    assertEventually(
        Duration.ofSeconds(30),
        () ->
            assertEquals(
                PendingActivityState.PENDING_ACTIVITY_STATE_PAUSED,
                handle.describe().getRunState()));

    handle.reset(ResetActivityOptions.newBuilder().setKeepPaused(true).build());

    // keep_paused keeps the activity paused across the reset.
    assertEventually(
        Duration.ofSeconds(30),
        () ->
            assertEquals(
                "expected activity to stay paused after reset",
                PendingActivityState.PENDING_ACTIVITY_STATE_PAUSED,
                handle.describe().getRunState()));
    handle.terminate("cleanup");
  }

  @Test(timeout = 60_000)
  public void resetRestoresOriginalOptions() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<Void> handle =
        startRunningSlowActivity(slowOpts().setStartToCloseTimeout(Duration.ofSeconds(45)));

    UpdateActivityOptions updated =
        handle.updateOptions(
            UpdateActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(90))
                .build());
    assertEquals(Duration.ofSeconds(90), updated.getStartToCloseTimeout());

    handle.reset(ResetActivityOptions.newBuilder().setRestoreOriginalOptions(true).build());

    // restore_original_options reverts start_to_close back to the value the activity started with.
    assertEventually(
        Duration.ofSeconds(30),
        () ->
            assertEquals(
                "start_to_close should be restored to original",
                Duration.ofSeconds(45),
                handle.describe().getStartToCloseTimeout()));
    handle.terminate("cleanup");
  }

  /**
   * Describe reports a paused activity as PAUSED (api#834), on both the execution status and the
   * run state. Asserts the transition, not just the end state: the same handle reports RUNNING
   * before the pause.
   */
  @Test(timeout = 60_000)
  public void describeReportsPausedStatus() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    // Start delayed so the activity sits SCHEDULED; pausing from there reaches a true PAUSED state
    // rather than the PAUSE_REQUESTED of a running activity.
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId(uniqueId())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .setStartDelay(Duration.ofSeconds(30))
            .build();
    ActivityHandle<String> handle =
        newActivityClient().start(QuickActivity.class, QuickActivity::run, opts);

    assertEquals(
        ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_RUNNING, handle.describe().getStatus());

    handle.pause(PauseActivityOptions.newBuilder().setReason("hold").build());

    assertEventually(
        Duration.ofSeconds(30),
        () -> {
          ActivityExecutionDescription desc = handle.describe();
          assertEquals(ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_PAUSED, desc.getStatus());
          assertEquals(PendingActivityState.PENDING_ACTIVITY_STATE_PAUSED, desc.getRunState());
        });

    handle.terminate("cleanup");
  }

  /** The count tracks heartbeats the server recorded. */
  @Test(timeout = 60_000)
  public void describeReportsTotalHeartbeatCount() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<Void> handle =
        startRunningSlowActivity(slowOpts().setHeartbeatTimeout(Duration.ofSeconds(3)));

    assertEventually(
        Duration.ofSeconds(20),
        () ->
            assertTrue(
                "total heartbeat count should reach 2",
                handle.describe().getTotalHeartbeatCount() >= 2));
    handle.terminate("cleanup");
  }

  @Test(timeout = 60_000)
  public void describePayloads() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId(uniqueId())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .setHeartbeatTimeout(Duration.ofSeconds(5))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
            .build();
    ActivityHandle<Integer> handle =
        newActivityClient()
            .start(
                HeartbeatFailIncrementActivity.class, HeartbeatFailIncrementActivity::run, opts, 1);
    assertEquals(Integer.valueOf(2), handle.getResult(Integer.class));

    // Nothing requested: every payload field is absent.
    ActivityExecutionDescription bare = handle.describe();
    assertFalse(bare.hasInput());
    assertFalse(bare.hasResult());
    assertFalse(bare.hasHeartbeatDetails());
    assertFalse(bare.hasLastFailure());
    assertFalse(bare.getResult(Integer.class).isPresent());
    assertNull(bare.getOutcomeFailure());
    assertNull(bare.getLastFailure());

    // All four requested. The activity succeeded on its second attempt, so it has a result and a
    // last failure at the same time, and no terminal failure.
    ActivityExecutionDescription full =
        handle.describe(
            DescribeActivityOptions.newBuilder()
                .setIncludeInput(true)
                .setIncludeOutcome(true)
                .setIncludeHeartbeatDetails(true)
                .setIncludeLastFailure(true)
                .build());
    assertTrue(full.hasInput());
    assertEquals(Integer.valueOf(1), full.getInput().get(0, Integer.class));
    assertTrue(full.hasResult());
    assertEquals(Integer.valueOf(2), full.getResult(Integer.class).orElse(null));
    assertNull(full.getOutcomeFailure());
    assertTrue(full.hasHeartbeatDetails());
    assertEquals("heartbeat details", full.getHeartbeatDetails().get(0, String.class));
    assertTrue(full.hasLastFailure());
    assertNotNull(full.getLastFailure());

    StartActivityOptions failOpts =
        StartActivityOptions.newBuilder()
            .setId(uniqueId())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
            .build();
    ActivityHandle<String> failed =
        newActivityClient()
            .start(FailThenSucceedActivity.class, FailThenSucceedActivity::run, failOpts);
    assertThrows(Exception.class, () -> failed.getResult(String.class));

    ActivityExecutionDescription desc =
        failed.describe(
            DescribeActivityOptions.newBuilder()
                .setIncludeOutcome(true)
                .setIncludeLastFailure(true)
                .build());
    assertFalse(desc.hasResult());
    assertFalse(desc.getResult(String.class).isPresent());
    assertTrue(desc.getOutcomeFailure() instanceof ApplicationFailure);
  }

  @Test(timeout = 60_000)
  public void pausePreservesHeartbeat() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<Void> handle = startHeartbeatReadyActivity();

    handle.pause(PauseActivityOptions.newBuilder().setReason("hold").build());
    assertEventuallyPaused(handle);

    // Pause never touches heartbeat details — they persist across the transition.
    assertTrue(
        "heartbeat details should be preserved across pause",
        handle.describe(WITH_HEARTBEAT_DETAILS).hasHeartbeatDetails());
    handle.terminate("cleanup");
  }

  @Test(timeout = 60_000)
  public void unpausePreservesHeartbeat() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<Void> handle = startHeartbeatReadyActivity();

    handle.pause(PauseActivityOptions.newBuilder().setReason("hold").build());
    assertEventuallyPaused(handle);

    // Unpause preserves heartbeat details. The re-dispatched attempt doesn't heartbeat (only
    // attempt 1 does), so the persisted details are stable and observable.
    handle.unpause();

    assertEventually(
        Duration.ofSeconds(30),
        () ->
            assertTrue(
                "heartbeat details should be preserved after unpause",
                handle.describe(WITH_HEARTBEAT_DETAILS).hasHeartbeatDetails()));
    handle.terminate("cleanup");
  }

  @Test(timeout = 60_000)
  public void updateOptionsPreservesHeartbeat() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<Void> handle = startHeartbeatReadyActivity();

    handle.pause(PauseActivityOptions.newBuilder().setReason("hold").build());
    assertEventuallyPaused(handle);

    // UpdateOptions changes activity options only; it never touches heartbeat details.
    handle.updateOptions(
        UpdateActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(90)).build());

    assertTrue(
        "heartbeat details should be preserved after updateOptions",
        handle.describe(WITH_HEARTBEAT_DETAILS).hasHeartbeatDetails());
    handle.terminate("cleanup");
  }

  // Overrides the rule's default 10s global timeout: exercises every command against a real server.
  @Test(timeout = 60_000)
  public void interceptorInvokesEachOperatorCommand() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    ActivityClient client =
        ActivityClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            ActivityClientOptions.newBuilder()
                .setNamespace(SDKTestWorkflowRule.NAMESPACE)
                .setInterceptors(Collections.singletonList(new RecordingInterceptor(events)))
                .build());

    ActivityHandle<Void> handle =
        client.start(SlowActivity.class, SlowActivity::run, slowOpts().build());
    assertEventually(
        Duration.ofSeconds(30),
        () ->
            assertEquals(
                PendingActivityState.PENDING_ACTIVITY_STATE_STARTED,
                handle.describe().getRunState()));

    handle.pause(PauseActivityOptions.newBuilder().setReason("reason").build());
    assertEventuallyPaused(handle);
    handle.unpause();
    handle.updateOptions(
        UpdateActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(90)).build());
    handle.reset();
    handle.terminate("cleanup");

    assertTrue("pause should flow through the interceptor", events.contains("pause"));
    assertTrue("unpause should flow through the interceptor", events.contains("unpause"));
    assertTrue("reset should flow through the interceptor", events.contains("reset"));
    assertTrue(
        "updateOptions should flow through the interceptor", events.contains("updateOptions"));
  }

  /** Records each operator command as it flows through the client interceptor chain. */
  private static class RecordingInterceptor extends ActivityClientInterceptorBase {
    private final List<String> events;

    RecordingInterceptor(List<String> events) {
      this.events = events;
    }

    @Override
    public ActivityClientCallsInterceptor activityClientCallsInterceptor(
        ActivityClientCallsInterceptor next) {
      return new ActivityClientCallsInterceptorBase(next) {
        @Override
        public PauseActivityOutput pauseActivity(PauseActivityInput input) {
          events.add("pause");
          return super.pauseActivity(input);
        }

        @Override
        public UnpauseActivityOutput unpauseActivity(UnpauseActivityInput input) {
          events.add("unpause");
          return super.unpauseActivity(input);
        }

        @Override
        public ResetActivityOutput resetActivity(ResetActivityInput input) {
          events.add("reset");
          return super.resetActivity(input);
        }

        @Override
        public UpdateActivityOptionsOutput updateActivityOptions(UpdateActivityOptionsInput input) {
          events.add("updateOptions");
          return super.updateActivityOptions(input);
        }
      };
    }
  }
}
