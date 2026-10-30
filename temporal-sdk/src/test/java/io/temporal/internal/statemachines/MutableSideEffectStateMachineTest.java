package io.temporal.internal.statemachines;

import static io.temporal.internal.statemachines.MutableSideEffectStateMachine.MARKER_DATA_KEY;
import static io.temporal.internal.statemachines.MutableSideEffectStateMachine.MARKER_ID_KEY;
import static io.temporal.internal.statemachines.MutableSideEffectStateMachine.MARKER_SKIP_COUNT_KEY;
import static io.temporal.internal.statemachines.MutableSideEffectStateMachine.MUTABLE_SIDE_EFFECT_MARKER_NAME;
import static io.temporal.internal.statemachines.TestHistoryBuilder.assertCommand;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.protobuf.Duration;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.api.history.v1.TimerFiredEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskCompletedEventAttributes;
import io.temporal.api.sdk.v1.WorkflowTaskCompletedMetadata;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.common.SdkFlag;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.AfterClass;
import org.junit.Test;

public class MutableSideEffectStateMachineTest {

  private final DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;
  private WorkflowStateMachines stateMachines;

  private static final List<
          StateMachine<
              MutableSideEffectStateMachine.State,
              MutableSideEffectStateMachine.ExplicitEvent,
              MutableSideEffectStateMachine.InvocationStateMachine>>
      stateMachineList = new ArrayList<>();

  private WorkflowStateMachines newStateMachines(TestEntityManagerListenerBase listener) {
    return new WorkflowStateMachines(listener, (stateMachineList::add));
  }

  @AfterClass
  public static void generateCoverage() {
    List<
            Transition<
                MutableSideEffectStateMachine.State,
                TransitionEvent<MutableSideEffectStateMachine.ExplicitEvent>>>
        missed =
            MutableSideEffectStateMachine.STATE_MACHINE_DEFINITION.getUnvisitedTransitions(
                stateMachineList);
    if (!missed.isEmpty()) {
      CommandsGeneratePlantUMLStateDiagramsTest.writeToFile(
          "test",
          MutableSideEffectStateMachine.class,
          MutableSideEffectStateMachine.STATE_MACHINE_DEFINITION.asPlantUMLStateDiagramCoverage(
              stateMachineList));
      fail(
          "MutableSideEffectStateMachine is missing test coverage for the following transitions:\n"
              + missed);
    }
  }

  @Test
  public void testOne() {
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Optional<Payloads>>add1(
                (v, c) ->
                    stateMachines.mutableSideEffect(
                        "id1", null, (p) -> converter.toPayloads("result1"), c))
            .add((r) -> stateMachines.completeWorkflow(r));
      }
    }
    /*
       1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
       2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
       3: EVENT_TYPE_WORKFLOW_TASK_STARTED
       4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
       5: EVENT_TYPE_MARKER_RECORDED
       6: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */
    MarkerRecordedEventAttributes.Builder markerBuilder =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(MUTABLE_SIDE_EFFECT_MARKER_NAME)
            .putDetails(MARKER_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_DATA_KEY, converter.toPayloads("result1").get())
                    .putDetails(MARKER_SKIP_COUNT_KEY, converter.toPayloads(0).get())
                    .build())
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    {
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);

      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
      Optional<Payloads> resultData =
          Optional.of(commands.get(1).getCompleteWorkflowExecutionCommandAttributes().getResult());
      assertEquals("result1", converter.fromPayloads(0, resultData, String.class, String.class));
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertTrue(commands.toString(), commands.isEmpty());
    }
  }

  @Test
  public void testDefaultThenRecord() {
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Optional<Payloads>>add1(
                (v, c) -> stateMachines.mutableSideEffect("id1", null, (p) -> Optional.empty(), c))
            .<Optional<Payloads>>add1(
                (v, c) -> stateMachines.mutableSideEffect("id1", null, (p) -> Optional.empty(), c))
            .<Optional<Payloads>>add1(
                (v, c) ->
                    stateMachines.mutableSideEffect(
                        "id1", null, (p) -> converter.toPayloads("result1"), c))
            .add((r) -> stateMachines.completeWorkflow(r));
      }
    }
    /*
      1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
      2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
      3: EVENT_TYPE_WORKFLOW_TASK_STARTED
      4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
      5: EVENT_TYPE_MARKER_RECORDED
      6: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */
    MarkerRecordedEventAttributes.Builder markerBuilder =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(MUTABLE_SIDE_EFFECT_MARKER_NAME)
            .putDetails(MARKER_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_DATA_KEY, converter.toPayloads("result1").get())
                    .putDetails(MARKER_SKIP_COUNT_KEY, converter.toPayloads(2).get())
                    .build())
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    {
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);

      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      int skipCount =
          converter.fromPayloads(
              0,
              Optional.ofNullable(
                  commands
                      .get(0)
                      .getRecordMarkerCommandAttributes()
                      .getDetailsOrThrow(MARKER_SKIP_COUNT_KEY)),
              Integer.class,
              Integer.class);
      assertEquals(2, skipCount);
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
      Optional<Payloads> resultData =
          Optional.of(commands.get(1).getCompleteWorkflowExecutionCommandAttributes().getResult());
      assertEquals("result1", converter.fromPayloads(0, resultData, String.class, String.class));
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertTrue(commands.isEmpty());
    }
  }

  @Test
  public void testSkippedInCommandlessTaskWithRecordedRecoveryFlag() {
    List<String> results = new ArrayList<>();
    boolean[] live = {true};
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        readValue(builder);
      }

      @Override
      protected void signal(HistoryEvent event, AsyncWorkflowBuilder<Void> builder) {
        if (event.getWorkflowExecutionSignaledEventAttributes().getSignalName().equals("skip")) {
          readValue(builder);
        } else {
          builder.add(v -> stateMachines.completeWorkflow(Optional.empty()));
        }
      }

      private void readValue(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Optional<Payloads>>add1(
                (v, callback) ->
                    stateMachines.mutableSideEffect(
                        "id1",
                        null,
                        previous -> {
                          assertTrue(
                              "Replayed mutable side effect must not execute its function.",
                              live[0]);
                          return previous.isPresent()
                              ? Optional.empty()
                              : converter.toPayloads("value");
                        },
                        callback))
            .add(
                result ->
                    results.add(converter.fromPayloads(0, result, String.class, String.class)));
      }
    }
    TestHistoryBuilder history =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTaskScheduledAndStarted()
            .add(
                EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED,
                WorkflowTaskCompletedEventAttributes.newBuilder()
                    .setScheduledEventId(2)
                    .setStartedEventId(3)
                    .setSdkMetadata(
                        WorkflowTaskCompletedMetadata.newBuilder()
                            .addLangUsedFlags(
                                SdkFlag.MUTABLE_SIDE_EFFECT_REPLAY_RECOVERY.getValue()))
                    .build())
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                MarkerRecordedEventAttributes.newBuilder()
                    .setMarkerName(MUTABLE_SIDE_EFFECT_MARKER_NAME)
                    .putDetails(MARKER_ID_KEY, converter.toPayloads("id1").get())
                    .putDetails(MARKER_DATA_KEY, converter.toPayloads("value").get())
                    .putDetails(MARKER_SKIP_COUNT_KEY, converter.toPayloads(0).get())
                    .build())
            .add(
                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
                WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("skip").build())
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
                WorkflowExecutionSignaledEventAttributes.newBuilder()
                    .setSignalName("finish")
                    .build())
            .addWorkflowTask()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    stateMachines = newStateMachines(new TestListener());
    assertCommand(
        CommandType.COMMAND_TYPE_RECORD_MARKER,
        history.handleWorkflowTaskTakeCommands(stateMachines, 1));
    assertEquals(Arrays.asList("value"), results);
    assertTrue(history.handleWorkflowTaskTakeCommands(stateMachines, 2).isEmpty());
    assertEquals(Arrays.asList("value", "value"), results);
    assertTrue(
        "Reading a recorded flag must not activate a new flag",
        stateMachines.takeNewSdkFlags().isEmpty());

    // A fresh worker replays the commandless task before executing the final signal.
    live[0] = false;
    results.clear();
    stateMachines = newStateMachines(new TestListener());
    assertCommand(
        CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION,
        history.handleWorkflowTaskTakeCommands(stateMachines, 3));
    assertEquals(Arrays.asList("value", "value"), results);

    // The same history must also replay to completion without emitting commands.
    results.clear();
    stateMachines = newStateMachines(new TestListener());
    assertTrue(history.handleWorkflowTaskTakeCommands(stateMachines).isEmpty());
    assertEquals(Arrays.asList("value", "value"), results);
  }

  @Test
  public void testRecoveryFlagIsRecordedAtMutableSideEffectCall() {
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {}

      @Override
      protected void signal(HistoryEvent event, AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Optional<Payloads>>add1(
                (v, callback) ->
                    stateMachines.mutableSideEffect(
                        "id1", null, previous -> converter.toPayloads("value"), callback))
            .add(stateMachines::completeWorkflow);
      }
    }
    TestHistoryBuilder history =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
                WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("use").build())
            .addWorkflowTaskScheduledAndStarted()
            .add(
                EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED,
                WorkflowTaskCompletedEventAttributes.newBuilder()
                    .setScheduledEventId(6)
                    .setStartedEventId(7)
                    .setSdkMetadata(
                        WorkflowTaskCompletedMetadata.newBuilder()
                            .addLangUsedFlags(
                                SdkFlag.MUTABLE_SIDE_EFFECT_REPLAY_RECOVERY.getValue()))
                    .build())
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                MarkerRecordedEventAttributes.newBuilder()
                    .setMarkerName(MUTABLE_SIDE_EFFECT_MARKER_NAME)
                    .putDetails(MARKER_ID_KEY, converter.toPayloads("id1").get())
                    .putDetails(MARKER_DATA_KEY, converter.toPayloads("value").get())
                    .putDetails(MARKER_SKIP_COUNT_KEY, converter.toPayloads(0).get())
                    .build())
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    stateMachines =
        new WorkflowStateMachines(
            new TestListener(),
            stateMachineList::add,
            GetSystemInfoResponse.Capabilities.newBuilder().setSdkMetadata(true).build(),
            WorkflowImplementationOptions.newBuilder().build());
    assertTrue(history.handleWorkflowTaskTakeCommands(stateMachines, 1).isEmpty());
    assertFalse(
        stateMachines.takeNewSdkFlags().contains(SdkFlag.MUTABLE_SIDE_EFFECT_REPLAY_RECOVERY));

    List<SdkFlag> savedInitialFlags = WorkflowStateMachines.initialFlags;
    List<SdkFlag> enabledFlags = new ArrayList<>(savedInitialFlags);
    enabledFlags.add(SdkFlag.MUTABLE_SIDE_EFFECT_REPLAY_RECOVERY);
    WorkflowStateMachines.initialFlags = Collections.unmodifiableList(enabledFlags);
    try {
      List<Command> commands = history.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
      assertEquals(
          EnumSet.of(SdkFlag.MUTABLE_SIDE_EFFECT_REPLAY_RECOVERY), stateMachines.takeNewSdkFlags());
    } finally {
      WorkflowStateMachines.initialFlags = savedInitialFlags;
    }
    stateMachines = newStateMachines(new TestListener());
    assertTrue(history.handleWorkflowTaskTakeCommands(stateMachines).isEmpty());
  }

  @Test
  public void testRecordAcrossMultipleWorkflowTasks() {
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Optional<Payloads>>add1(
                (v, c) ->
                    stateMachines.mutableSideEffect(
                        "id1", null, (p) -> converter.toPayloads("result1"), c))
            .<Optional<Payloads>>add1(
                (v, c) -> stateMachines.mutableSideEffect("id1", null, (p) -> Optional.empty(), c))
            .<HistoryEvent>add1(
                (v, c) ->
                    stateMachines.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        null,
                        c))
            .<HistoryEvent>add1(
                (v, c) ->
                    stateMachines.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        null,
                        c))
            .<Optional<Payloads>>add1(
                (v, c) -> stateMachines.mutableSideEffect("id1", null, (p) -> Optional.empty(), c))
            .<Optional<Payloads>>add1(
                (v, c) ->
                    stateMachines.mutableSideEffect(
                        "id1", null, (p) -> converter.toPayloads("result2"), c))
            .add((r) -> stateMachines.completeWorkflow(r));
      }
    }

    /*
      1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
      2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
      3: EVENT_TYPE_WORKFLOW_TASK_STARTED
      4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
      5: EVENT_TYPE_MARKER_RECORDED
      6: EVENT_TYPE_TIMER_STARTED
      7: EVENT_TYPE_TIMER_FIRED
      8: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
      9: EVENT_TYPE_WORKFLOW_TASK_STARTED
      10: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
      11: EVENT_TYPE_TIMER_STARTED
      12: EVENT_TYPE_TIMER_FIRED
      13: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
      14: EVENT_TYPE_WORKFLOW_TASK_STARTED
      15: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
      16: EVENT_TYPE_MARKER_RECORDED
      17: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */
    MarkerRecordedEventAttributes.Builder markerBuilder =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(MUTABLE_SIDE_EFFECT_MARKER_NAME)
            .putDetails(MARKER_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_DATA_KEY, converter.toPayloads("result1").get())
                    .putDetails(MARKER_SKIP_COUNT_KEY, converter.toPayloads(0).get())
                    .build());
    long timerStartedEventId1 = h.addGetEventId(EventType.EVENT_TYPE_TIMER_STARTED);
    h.add(
            EventType.EVENT_TYPE_TIMER_FIRED,
            TimerFiredEventAttributes.newBuilder()
                .setStartedEventId(timerStartedEventId1)
                .setTimerId("timer1"))
        .addWorkflowTask();
    long timerStartedEventId2 = h.addGetEventId(EventType.EVENT_TYPE_TIMER_STARTED);
    h.add(
            EventType.EVENT_TYPE_TIMER_FIRED,
            TimerFiredEventAttributes.newBuilder()
                .setStartedEventId(timerStartedEventId2)
                .setTimerId("timer2"))
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_MARKER_RECORDED,
            markerBuilder
                .putDetails(MARKER_DATA_KEY, converter.toPayloads("result2").get())
                .putDetails(MARKER_SKIP_COUNT_KEY, converter.toPayloads(2).get())
                .build())
        .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    {
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);

      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      int skipCount =
          converter.fromPayloads(
              0,
              Optional.ofNullable(
                  commands
                      .get(0)
                      .getRecordMarkerCommandAttributes()
                      .getDetailsOrThrow(MARKER_SKIP_COUNT_KEY)),
              Integer.class,
              Integer.class);
      assertEquals(0, skipCount);
      assertEquals(CommandType.COMMAND_TYPE_START_TIMER, commands.get(1).getCommandType());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_START_TIMER, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);

      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      int skipCount =
          converter.fromPayloads(
              0,
              Optional.ofNullable(
                  commands
                      .get(0)
                      .getRecordMarkerCommandAttributes()
                      .getDetailsOrThrow(MARKER_SKIP_COUNT_KEY)),
              Integer.class,
              Integer.class);
      assertEquals(2, skipCount);
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
      Optional<Payloads> resultData =
          Optional.of(commands.get(1).getCompleteWorkflowExecutionCommandAttributes().getResult());
      assertEquals("result2", converter.fromPayloads(0, resultData, String.class, String.class));
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertTrue(commands.isEmpty());
    }
  }
}
