package io.temporal.internal.testservice;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.protobuf.util.Durations;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.command.v1.*;
import io.temporal.api.common.v1.*;
import io.temporal.api.enums.v1.*;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.*;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestWorkflowMutableStateActivityAndSignalLocksTest {
  private static final String NAMESPACE = "namespace";
  private static final TaskQueue QUEUE = TaskQueue.newBuilder().setName("queue").build();
  private SelfAdvancingTimerImpl timer;
  private TestWorkflowStoreImpl store;
  private TestWorkflowService service;
  private SelfAdvancingTimer timerService;
  private final List<CountDownLatch> activityTimeouts = new ArrayList<>();
  private LockHandle testLock;

  @Before
  public void setUp() {
    timer = new SelfAdvancingTimerImpl(System.currentTimeMillis(), Clock.systemUTC());
    testLock = timer.lockTimeSkipping("test");
    timerService = mock(SelfAdvancingTimer.class, org.mockito.AdditionalAnswers.delegatesTo(timer));
    doAnswer(
            invocation -> {
              Duration delay = invocation.getArgument(0);
              Runnable callback = invocation.getArgument(1);
              String name = invocation.getArgument(2);
              if (!name.startsWith("Activity")) {
                return timer.schedule(delay, callback, name);
              }
              CountDownLatch finished = new CountDownLatch(1);
              activityTimeouts.add(finished);
              return timer.schedule(
                  delay,
                  () -> {
                    try {
                      callback.run();
                    } finally {
                      finished.countDown();
                    }
                  },
                  name);
            })
        .when(timerService)
        .schedule(any(Duration.class), any(Runnable.class), anyString());
    store = new TestWorkflowStoreImpl(timerService);
    service = mock(TestWorkflowService.class);
  }

  @After
  public void tearDown() {
    store.close();
    timer.shutdown();
  }

  @Test
  public void scheduledActivityOnTermination() throws Exception {
    TestWorkflowMutableStateImpl state = newWorkflow();
    completeTask(state, activityCommand("activity"));
    state.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.getDefaultInstance());
    assertClockBalanced();
  }

  @Test
  public void startedActivityOnTerminationAndLateResponses() throws Exception {
    TestWorkflowMutableStateImpl state = newWorkflow();
    completeTask(state, activityCommand("activity"));
    PollActivityTaskQueueResponse task = startActivity(state);
    state.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.getDefaultInstance());
    assertNotFound(
        () ->
            state.completeActivityTaskById(
                "activity", RespondActivityTaskCompletedByIdRequest.getDefaultInstance()));
    assertNotFound(
        () ->
            state.failActivityTaskById(
                "activity", RespondActivityTaskFailedByIdRequest.getDefaultInstance()));
    assertNotFound(
        () ->
            state.cancelActivityTaskById(
                "activity", RespondActivityTaskCanceledByIdRequest.getDefaultInstance()));
    ActivityTaskToken token = ActivityTaskToken.fromBytes(task.getTaskToken());
    assertNotFound(
        () ->
            state.completeActivityTask(
                token.getScheduledEventId(),
                RespondActivityTaskCompletedRequest.newBuilder()
                    .setTaskToken(task.getTaskToken())
                    .build()));
    assertNotFound(
        () ->
            state.failActivityTask(
                token.getScheduledEventId(),
                RespondActivityTaskFailedRequest.newBuilder()
                    .setTaskToken(task.getTaskToken())
                    .build()));
    assertNotFound(
        () ->
            state.cancelActivityTask(
                token.getScheduledEventId(),
                RespondActivityTaskCanceledRequest.newBuilder()
                    .setTaskToken(task.getTaskToken())
                    .build()));
    assertClockBalanced();
  }

  @Test
  public void activityOnWorkflowCompletion() throws Exception {
    TestWorkflowMutableStateImpl state = newWorkflow();
    completeTask(
        state,
        activityCommand("activity"),
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION)
            .setCompleteWorkflowExecutionCommandAttributes(
                CompleteWorkflowExecutionCommandAttributes.getDefaultInstance())
            .build());
    assertClockBalanced();
  }

  @Test
  public void activityOnWorkflowFailure() throws Exception {
    TestWorkflowMutableStateImpl state = newWorkflow();
    completeTask(
        state,
        activityCommand("activity"),
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION)
            .setFailWorkflowExecutionCommandAttributes(
                FailWorkflowExecutionCommandAttributes.newBuilder().setFailure(failure()))
            .build());
    assertClockBalanced();
  }

  @Test
  public void activityOnWorkflowCancellation() throws Exception {
    TestWorkflowMutableStateImpl state = newWorkflow();
    state.requestCancelWorkflowExecution(
        RequestCancelWorkflowExecutionRequest.getDefaultInstance(), Optional.empty());
    completeTask(
        state,
        activityCommand("activity"),
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION)
            .setCancelWorkflowExecutionCommandAttributes(
                CancelWorkflowExecutionCommandAttributes.getDefaultInstance())
            .build());
    assertClockBalanced();
  }

  @Test
  public void activityOnRunTimeoutAndLateActivityTimeouts() throws Exception {
    TestWorkflowMutableStateImpl state = newWorkflow();
    timer.skip(Duration.ofSeconds(3400));
    completeTask(state, activityCommand("activity"));
    startActivity(state);
    timer.skip(Duration.ofSeconds(201));
    CountDownLatch callbacks = new CountDownLatch(1);
    timer.schedule(Duration.ZERO, callbacks::countDown);
    assertTrue(callbacks.await(5, TimeUnit.SECONDS));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!state.isTerminalState() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(state.isTerminalState());
    timer.skip(Duration.ofHours(2));
    for (CountDownLatch finished : activityTimeouts) {
      assertTrue(finished.await(5, TimeUnit.SECONDS));
    }
    assertClockBalanced();
  }

  @Test
  public void retryBackoffOnTermination() throws Exception {
    TestWorkflowMutableStateImpl state = newWorkflow();
    completeTask(state, activityCommand("activity"));
    startActivity(state);
    state.failActivityTaskById(
        "activity",
        RespondActivityTaskFailedByIdRequest.newBuilder().setFailure(failure()).build());
    state.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.getDefaultInstance());
    assertClockBalanced();
  }

  @Test
  public void retryAttemptOnTermination() throws Exception {
    TestWorkflowMutableStateImpl state = newWorkflow();
    completeTask(state, activityCommand("activity"));
    startActivity(state);
    state.failActivityTaskById(
        "activity",
        RespondActivityTaskFailedByIdRequest.newBuilder().setFailure(failure()).build());
    timer.skip(Duration.ofMinutes(1));
    PollActivityTaskQueueResponse task = startActivity(state);
    assertEquals(2, task.getAttempt());
    state.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.getDefaultInstance());
    assertClockBalanced();
  }

  @Test
  public void signalSuccessAfterSourceCloses() throws Exception {
    signalAfterSourceCloses(true);
  }

  @Test
  public void signalFailureAfterSourceCloses() throws Exception {
    signalAfterSourceCloses(false);
  }

  @Test
  public void scheduledActivityCancellation() throws Exception {
    TestWorkflowMutableStateImpl state = newWorkflow();
    completeTask(state, activityCommand("activity"), cancelActivityCommand());
    completeTask(state);
    assertClockBalanced();
  }

  @Test
  public void retryBackoffCancellation() throws Exception {
    TestWorkflowMutableStateImpl state = newWorkflow();
    completeTask(state, activityCommand("activity"));
    startActivity(state);
    state.failActivityTaskById(
        "activity",
        RespondActivityTaskFailedByIdRequest.newBuilder().setFailure(failure()).build());
    state.signal(SignalWorkflowExecutionRequest.newBuilder().setSignalName("cancel").build());
    completeTask(state, cancelActivityCommand());
    completeTask(state);
    assertClockBalanced();
  }

  @Test
  public void rejectedCommandBatchDoesNotOwnActivityLocks() throws Exception {
    TestWorkflowMutableStateImpl state = newWorkflow();
    StatusRuntimeException failure =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                completeTask(
                    state, activityCommand("a"), activityCommand("b"), activityCommand("a")));
    assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    state.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.getDefaultInstance());
    // The rejected context also retains its workflow task lock, outside this fix.
    timer.unlockTimeSkipping("rejected workflow task");
    assertClockBalanced();
  }

  @Test
  public void rejectedCommandBatchDoesNotOwnSignalLocks() throws Exception {
    List<String> signalIds = new CopyOnWriteArrayList<>();
    CountDownLatch dispatched = new CountDownLatch(2);
    doAnswer(
            invocation -> {
              signalIds.add(invocation.getArgument(0));
              dispatched.countDown();
              return null;
            })
        .when(service)
        .signalExternalWorkflowExecution(anyString(), any(), any());
    TestWorkflowMutableStateImpl state = newWorkflow();
    StatusRuntimeException failure =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                completeTask(
                    state,
                    signalCommand(),
                    signalCommand(),
                    activityCommand("a"),
                    activityCommand("a")));
    assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    assertTrue(dispatched.await(5, TimeUnit.SECONDS));
    state.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.getDefaultInstance());
    assertNotFound(
        () -> state.completeSignalExternalWorkflowExecution(signalIds.get(0), "target-run"));
    assertNotFound(
        () ->
            state.failSignalExternalWorkflowExecution(
                signalIds.get(1),
                SignalExternalWorkflowExecutionFailedCause
                    .SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND));
    // The rejected context also retains its workflow task lock, outside this fix.
    timer.unlockTimeSkipping("rejected workflow task");
    assertClockBalanced();
  }

  private Command signalCommand() {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION)
        .setSignalExternalWorkflowExecutionCommandAttributes(
            SignalExternalWorkflowExecutionCommandAttributes.newBuilder()
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId("target"))
                .setSignalName("signal"))
        .build();
  }

  private Command cancelActivityCommand() {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK)
        .setRequestCancelActivityTaskCommandAttributes(
            RequestCancelActivityTaskCommandAttributes.newBuilder().setScheduledEventId(5))
        .build();
  }

  private void signalAfterSourceCloses(boolean success) throws Exception {
    AtomicReference<String> signalId = new AtomicReference<>();
    CountDownLatch dispatched = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              signalId.set(invocation.getArgument(0));
              dispatched.countDown();
              return null;
            })
        .when(service)
        .signalExternalWorkflowExecution(anyString(), any(), any());
    TestWorkflowMutableStateImpl state = newWorkflow();
    completeTask(state, signalCommand());
    assertTrue(dispatched.await(5, TimeUnit.SECONDS));
    state.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.getDefaultInstance());
    if (success) {
      assertNotFound(
          () -> state.completeSignalExternalWorkflowExecution(signalId.get(), "target-run"));
      assertClockBalanced();
    }
    assertNotFound(
        () ->
            state.failSignalExternalWorkflowExecution(
                signalId.get(),
                SignalExternalWorkflowExecutionFailedCause
                    .SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND));
    assertClockBalanced();
  }

  private TestWorkflowMutableStateImpl newWorkflow() {
    String runId = UUID.randomUUID().toString();
    TestWorkflowMutableStateImpl state =
        new TestWorkflowMutableStateImpl(
            StartWorkflowExecutionRequest.newBuilder()
                .setNamespace(NAMESPACE)
                .setWorkflowId(runId)
                .setRequestId(runId)
                .setWorkflowType(WorkflowType.newBuilder().setName("workflow"))
                .setTaskQueue(QUEUE)
                .setWorkflowRunTimeout(Durations.fromSeconds(3600))
                .setWorkflowTaskTimeout(Durations.fromSeconds(600))
                .build(),
            runId,
            runId,
            Optional.empty(),
            Duration.ZERO,
            null,
            Optional.empty(),
            Optional.empty(),
            OptionalLong.empty(),
            Optional.empty(),
            service,
            store,
            mock(TestVisibilityStore.class),
            mock(TestNexusEndpointStore.class),
            timerService);
    state.startWorkflow(false, null, null);
    return state;
  }

  private void completeTask(TestWorkflowMutableStateImpl state, Command... commands)
      throws Exception {
    PollWorkflowTaskQueueRequest poll =
        PollWorkflowTaskQueueRequest.newBuilder()
            .setNamespace(NAMESPACE)
            .setTaskQueue(QUEUE)
            .build();
    PollWorkflowTaskQueueResponse.Builder task =
        store.pollWorkflowTaskQueue(poll).get(5, TimeUnit.SECONDS);
    state.startWorkflowTask(task, poll);
    state.completeWorkflowTask(
        task.getHistory().getEventsCount(),
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .setTaskToken(task.getTaskToken())
            .addAllCommands(Arrays.asList(commands))
            .build());
  }

  private PollActivityTaskQueueResponse startActivity(TestWorkflowMutableStateImpl state)
      throws Exception {
    PollActivityTaskQueueRequest poll =
        PollActivityTaskQueueRequest.newBuilder()
            .setNamespace(NAMESPACE)
            .setTaskQueue(QUEUE)
            .build();
    PollActivityTaskQueueResponse.Builder task =
        store.pollActivityTaskQueue(poll).get(5, TimeUnit.SECONDS);
    while (!task.getWorkflowExecution().equals(state.getExecutionId().getExecution())) {
      task = store.pollActivityTaskQueue(poll).get(5, TimeUnit.SECONDS);
    }
    state.startActivityTask(task, poll);
    return task.build();
  }

  private Command activityCommand(String id) {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK)
        .setScheduleActivityTaskCommandAttributes(
            ScheduleActivityTaskCommandAttributes.newBuilder()
                .setActivityId(id)
                .setActivityType(ActivityType.newBuilder().setName("activity"))
                .setTaskQueue(QUEUE)
                .setScheduleToCloseTimeout(Durations.fromSeconds(3000))
                .setScheduleToStartTimeout(Durations.fromSeconds(2000))
                .setStartToCloseTimeout(Durations.fromSeconds(1000))
                .setHeartbeatTimeout(Durations.fromSeconds(500))
                .setRetryPolicy(
                    RetryPolicy.newBuilder()
                        .setInitialInterval(Durations.fromSeconds(60))
                        .setMaximumAttempts(3)))
        .build();
  }

  private Failure failure() {
    return Failure.newBuilder()
        .setMessage("failure")
        .setApplicationFailureInfo(ApplicationFailureInfo.getDefaultInstance())
        .build();
  }

  private void assertNotFound(Runnable response) {
    StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, response::run);
    assertEquals(Status.Code.NOT_FOUND, failure.getStatus().getCode());
  }

  private void assertClockBalanced() throws Exception {
    TestWorkflowMutableStateImpl other = newWorkflow();
    completeTask(other, activityCommand("guard"));
    startActivity(other);
    CountDownLatch futureTimer = new CountDownLatch(1);
    timer.schedule(Duration.ofMinutes(1), futureTimer::countDown);
    testLock.unlock();
    assertFalse(
        "A live activity must prevent time skipping",
        futureTimer.await(100, TimeUnit.MILLISECONDS));
    testLock = timer.lockTimeSkipping("finish guard");
    other.completeActivityTaskById(
        "guard", RespondActivityTaskCompletedByIdRequest.getDefaultInstance());
    completeTask(
        other,
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION)
            .setCompleteWorkflowExecutionCommandAttributes(
                CompleteWorkflowExecutionCommandAttributes.getDefaultInstance())
            .build());
    testLock.unlock();
    assertTrue("A future timer must fire automatically", futureTimer.await(5, TimeUnit.SECONDS));
    testLock = timer.lockTimeSkipping("test");
  }
}
