package io.temporal.client.functional;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.enums.v1.PendingActivityState;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityExecutionOptions;
import io.temporal.client.ActivityHandle;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.UpdateActivityOptions;
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

  // ---------------------------------------------------------------------------
  // Rule + helpers
  // ---------------------------------------------------------------------------

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setActivityImplementations(
              new SlowActivityImpl(), new QuickActivityImpl(), new FailThenSucceedActivityImpl())
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

  private void assertPaused(ActivityHandle<?> handle) {
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

  @Test
  public void pauseShowsPaused() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<Void> handle = startRunningSlowActivity(slowOpts());
    handle.pause("test-pause-reason");
    assertPaused(handle);
    handle.terminate("cleanup");
  }

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

    handle.pause("pause-before-unpause");
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

    ActivityExecutionOptions updated =
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

  @Test
  public void updateOptionsRestoreOriginalExclusive() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<Void> handle = startRunningSlowActivity(slowOpts());
    // Building the request with restore_original AND another option is rejected before any RPC.
    IllegalArgumentException err =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                handle.updateOptions(
                    UpdateActivityOptions.newBuilder()
                        .setRestoreOriginal(true)
                        .setStartToCloseTimeout(Duration.ofSeconds(5))
                        .build()));
    assertTrue(err.getMessage().toLowerCase().contains("restore"));
    handle.terminate("cleanup");
  }

  @Test
  public void updateOptionsRestoreOriginalAlone() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<Void> handle =
        startRunningSlowActivity(slowOpts().setStartToCloseTimeout(Duration.ofSeconds(45)));

    // Change an option away from the original.
    ActivityExecutionOptions changed =
        handle.updateOptions(
            UpdateActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(90))
                .build());
    assertEquals(Duration.ofSeconds(90), changed.getStartToCloseTimeout());

    // restore_original alone reverts to the value the activity was created with.
    ActivityExecutionOptions restored =
        handle.updateOptions(UpdateActivityOptions.newBuilder().setRestoreOriginal(true).build());
    assertEquals(Duration.ofSeconds(45), restored.getStartToCloseTimeout());
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

    handle.pause("reason");
    assertPaused(handle);
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
