package io.temporal.workflow;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_CANCELED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_FIRED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_STARTED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class PromiseTimedGetCancellationTest {

  private static final Duration LONG_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);

  private final CountDownLatch activityFinished = new CountDownLatch(1);
  private final CountDownLatch activityStarted = new CountDownLatch(1);

  @Parameterized.Parameters(name = "detachedTimer={0}, cancelAwaitTimer={1}")
  public static Collection<Object[]> parameters() {
    return Arrays.asList(
        new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}});
  }

  private final boolean detachedTimer;
  private final boolean cancelAwaitTimer;
  private List<SdkFlag> savedInitialFlags;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              ControlledPromiseWorkflowImpl.class,
              ActivityCancellationWorkflowImpl.class,
              ChildStartCancellationWorkflowImpl.class,
              ReplayWorkflowImpl.class,
              TestChildWorkflowImpl.class)
          .setActivityImplementations(
              new CancellableActivityImpl(activityStarted, activityFinished))
          .setUseTimeskipping(false)
          .build();

  public PromiseTimedGetCancellationTest(boolean detachedTimer, boolean cancelAwaitTimer) {
    this.detachedTimer = detachedTimer;
    this.cancelAwaitTimer = cancelAwaitTimer;
  }

  @Before
  public void setUpSdkFlags() {
    savedInitialFlags = WorkflowStateMachines.initialFlags;
    List<SdkFlag> flags = new ArrayList<>(savedInitialFlags);
    if (detachedTimer) {
      flags.add(SdkFlag.DETACH_NON_CANCELLABLE_PROMISE_GET_TIMER);
    }
    if (cancelAwaitTimer) {
      flags.add(SdkFlag.CANCEL_AWAIT_TIMER_ON_CONDITION);
    }
    WorkflowStateMachines.initialFlags = Collections.unmodifiableList(flags);
  }

  @After
  public void restoreSdkFlags() {
    WorkflowStateMachines.initialFlags = savedInitialFlags;
  }

  @Test
  public void nonCancellableGetDoesNotTreatCancellationAsTimeoutWhenFlagEnabled() {
    ControlledPromiseWorkflow workflow =
        testWorkflowRule.newWorkflowStub(ControlledPromiseWorkflow.class);
    WorkflowExecution execution =
        WorkflowClient.start(workflow::execute, LONG_TIMEOUT.toMillis(), false);
    assertTrue(workflow.isWaiting());

    workflow.cancelTimedWait();
    if (detachedTimer) {
      workflow.complete();
    }

    assertEquals(
        detachedTimer ? "completed" : "timeout",
        WorkflowStub.fromTyped(workflow).getResult(String.class));
    testWorkflowRule.assertHistoryEvent(execution.getWorkflowId(), EVENT_TYPE_TIMER_STARTED);
    testWorkflowRule.assertHistoryEvent(execution.getWorkflowId(), EVENT_TYPE_TIMER_CANCELED);
    testWorkflowRule.assertNoHistoryEvent(execution.getWorkflowId(), EVENT_TYPE_TIMER_FIRED);
  }

  @Test
  public void cancellableGetRemainsCancellationSensitive() {
    ControlledPromiseWorkflow workflow =
        testWorkflowRule.newWorkflowStub(ControlledPromiseWorkflow.class);
    WorkflowClient.start(workflow::execute, LONG_TIMEOUT.toMillis(), true);
    assertTrue(workflow.isWaiting());

    workflow.cancelTimedWait();

    assertEquals("canceled", WorkflowStub.fromTyped(workflow).getResult(String.class));
  }

  @Test
  public void actualTimeoutStillThrowsTimeoutException() {
    ControlledPromiseWorkflow workflow =
        testWorkflowRule.newWorkflowStub(ControlledPromiseWorkflow.class);

    assertEquals("timeout", workflow.execute(SHORT_TIMEOUT.toMillis(), false));
  }

  @Test
  public void completedPromiseCancelsTimeoutTimerAccordingToEnabledFlags() {
    ControlledPromiseWorkflow workflow =
        testWorkflowRule.newWorkflowStub(ControlledPromiseWorkflow.class);
    WorkflowExecution execution =
        WorkflowClient.start(workflow::execute, LONG_TIMEOUT.toMillis(), false);
    assertTrue(workflow.isWaiting());

    workflow.complete();

    assertEquals("completed", WorkflowStub.fromTyped(workflow).getResult(String.class));
    testWorkflowRule.assertHistoryEvent(execution.getWorkflowId(), EVENT_TYPE_TIMER_STARTED);
    if (detachedTimer || cancelAwaitTimer) {
      testWorkflowRule.assertHistoryEvent(execution.getWorkflowId(), EVENT_TYPE_TIMER_CANCELED);
    } else {
      testWorkflowRule.assertNoHistoryEvent(execution.getWorkflowId(), EVENT_TYPE_TIMER_CANCELED);
    }
    testWorkflowRule.assertNoHistoryEvent(execution.getWorkflowId(), EVENT_TYPE_TIMER_FIRED);
  }

  @Test
  public void childStartCancellationDoesNotBecomeTimeoutWhenFlagEnabled() {
    assumeTrue(detachedTimer);
    ChildStartCancellationWorkflow workflow =
        testWorkflowRule.newWorkflowStub(ChildStartCancellationWorkflow.class);

    assertEquals("canceled", workflow.execute());
  }

  @Test
  public void activityCancellationHistoryReplaysWithItsFlagCombination() throws Exception {
    ActivityCancellationWorkflow workflow =
        testWorkflowRule.newWorkflowStub(ActivityCancellationWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    assertTrue(activityStarted.await(10, TimeUnit.SECONDS));

    workflow.cancelActivityWait();

    assertEquals(
        detachedTimer ? "activity-canceled" : "timeout",
        WorkflowStub.fromTyped(workflow).getResult(String.class));
    assertTrue(activityFinished.await(10, TimeUnit.SECONDS));
    WorkflowExecutionHistory history =
        testWorkflowRule.getExecutionHistory(execution.getWorkflowId(), execution.getRunId());
    WorkflowReplayer.replayWorkflowExecution(history, testWorkflowRule.getWorker());
    assertEquals(
        detachedTimer, hasSdkFlag(history, SdkFlag.DETACH_NON_CANCELLABLE_PROMISE_GET_TIMER));
    assertEquals(cancelAwaitTimer, hasSdkFlag(history, SdkFlag.CANCEL_AWAIT_TIMER_ON_CONDITION));
  }

  @Test
  public void generatedHistoryReplaysWithItsFlagCombination() throws Exception {
    ReplayWorkflow workflow = testWorkflowRule.newWorkflowStub(ReplayWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    assertEquals("timeout", WorkflowStub.fromTyped(workflow).getResult(String.class));

    WorkflowExecutionHistory history =
        testWorkflowRule.getExecutionHistory(execution.getWorkflowId(), execution.getRunId());
    WorkflowReplayer.replayWorkflowExecution(history, testWorkflowRule.getWorker());
    assertEquals(
        detachedTimer, hasSdkFlag(history, SdkFlag.DETACH_NON_CANCELLABLE_PROMISE_GET_TIMER));
    assertEquals(cancelAwaitTimer, hasSdkFlag(history, SdkFlag.CANCEL_AWAIT_TIMER_ON_CONDITION));
  }

  @WorkflowInterface
  public interface ControlledPromiseWorkflow {
    @WorkflowMethod
    String execute(long timeoutMillis, boolean cancellable);

    @SignalMethod
    void complete();

    @SignalMethod
    void cancelTimedWait();

    @QueryMethod
    boolean isWaiting();
  }

  public static class ControlledPromiseWorkflowImpl implements ControlledPromiseWorkflow {
    private CompletablePromise<String> promise;
    private CancellationScope timedWaitScope;
    private boolean waiting;

    @Override
    public String execute(long timeoutMillis, boolean cancellable) {
      AtomicReference<String> result = new AtomicReference<>();
      timedWaitScope =
          Workflow.newCancellationScope(
              () -> {
                promise = Workflow.newPromise();
                waiting = true;
                try {
                  result.set(
                      cancellable
                          ? promise.cancellableGet(timeoutMillis, TimeUnit.MILLISECONDS)
                          : promise.get(timeoutMillis, TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                  result.set("timeout");
                } catch (CanceledFailure e) {
                  result.set("canceled");
                }
              });
      timedWaitScope.run();
      return result.get();
    }

    @Override
    public void complete() {
      promise.complete("completed");
    }

    @Override
    public void cancelTimedWait() {
      timedWaitScope.cancel("test cancellation");
    }

    @Override
    public boolean isWaiting() {
      return waiting;
    }
  }

  @WorkflowInterface
  public interface ActivityCancellationWorkflow {
    @WorkflowMethod
    String execute();

    @SignalMethod
    void cancelActivityWait();
  }

  public static class ActivityCancellationWorkflowImpl implements ActivityCancellationWorkflow {
    private CancellationScope activityScope;

    @Override
    public String execute() {
      AtomicReference<String> outcome = new AtomicReference<>();
      activityScope =
          Workflow.newCancellationScope(
              () -> {
                CancellableActivity activity =
                    Workflow.newActivityStub(
                        CancellableActivity.class,
                        ActivityOptions.newBuilder()
                            .setScheduleToCloseTimeout(LONG_TIMEOUT)
                            .setStartToCloseTimeout(LONG_TIMEOUT)
                            .setHeartbeatTimeout(Duration.ofSeconds(1))
                            .setCancellationType(
                                ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                            .build());
                Promise<String> result = Async.function(activity::execute);
                try {
                  outcome.set(result.get(LONG_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                  outcome.set("timeout");
                } catch (ActivityFailure e) {
                  outcome.set(
                      e.getCause() instanceof CanceledFailure
                          ? "activity-canceled"
                          : "activity-failed");
                }
              });
      activityScope.run();
      return outcome.get();
    }

    @Override
    public void cancelActivityWait() {
      activityScope.cancel("test cancellation");
    }
  }

  @ActivityInterface
  public interface CancellableActivity {
    String execute();
  }

  public static class CancellableActivityImpl implements CancellableActivity {
    private final CountDownLatch activityStarted;
    private final CountDownLatch activityFinished;

    public CancellableActivityImpl(
        CountDownLatch activityStarted, CountDownLatch activityFinished) {
      this.activityStarted = activityStarted;
      this.activityFinished = activityFinished;
    }

    @Override
    public String execute() {
      activityStarted.countDown();
      try {
        while (true) {
          Activity.getExecutionContext().heartbeat(null);
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
        }
      } finally {
        activityFinished.countDown();
      }
    }
  }

  @WorkflowInterface
  public interface ChildStartCancellationWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class ChildStartCancellationWorkflowImpl implements ChildStartCancellationWorkflow {
    @Override
    public String execute() {
      AtomicReference<CancellationScope> scope = new AtomicReference<>();
      AtomicReference<String> outcome = new AtomicReference<>();
      scope.set(
          Workflow.newCancellationScope(
              () -> {
                TestChildWorkflow child = Workflow.newChildWorkflowStub(TestChildWorkflow.class);
                Async.procedure(child::execute).exceptionally((failure) -> null);
                Promise<WorkflowExecution> execution = Workflow.getWorkflowExecution(child);
                Async.procedure(() -> scope.get().cancel("test cancellation"));
                try {
                  execution.get(LONG_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                  outcome.set("started");
                } catch (TimeoutException e) {
                  outcome.set("timeout");
                } catch (ChildWorkflowFailure e) {
                  outcome.set(e.getCause() instanceof CanceledFailure ? "canceled" : "failed");
                } catch (CanceledFailure e) {
                  outcome.set("canceled");
                }
              }));
      scope.get().run();
      return outcome.get();
    }
  }

  @WorkflowInterface
  public interface TestChildWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class TestChildWorkflowImpl implements TestChildWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(Duration.ofHours(1));
    }
  }

  @WorkflowInterface
  public interface ReplayWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class ReplayWorkflowImpl implements ReplayWorkflow {
    @Override
    public String execute() {
      AtomicReference<CancellationScope> scope = new AtomicReference<>();
      AtomicReference<String> outcome = new AtomicReference<>();
      scope.set(
          Workflow.newCancellationScope(
              () -> {
                CompletablePromise<Void> promise = Workflow.newPromise();
                Async.procedure(() -> scope.get().cancel("test cancellation"));
                try {
                  promise.get(SHORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                  outcome.set("completed");
                } catch (TimeoutException e) {
                  outcome.set("timeout");
                }
              }));
      scope.get().run();
      return outcome.get();
    }
  }

  private static boolean hasSdkFlag(WorkflowExecutionHistory history, SdkFlag flag) {
    for (HistoryEvent event : history.getEvents()) {
      if (event.hasWorkflowTaskCompletedEventAttributes()
          && event
              .getWorkflowTaskCompletedEventAttributes()
              .getSdkMetadata()
              .getLangUsedFlagsList()
              .contains(flag.getValue())) {
        return true;
      }
    }
    return false;
  }
}
