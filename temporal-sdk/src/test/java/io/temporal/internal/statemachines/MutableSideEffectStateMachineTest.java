/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.statemachines;

import static io.temporal.internal.statemachines.MutableSideEffectStateMachine.MARKER_DATA_KEY;
import static io.temporal.internal.statemachines.MutableSideEffectStateMachine.MARKER_ID_KEY;
import static io.temporal.internal.statemachines.MutableSideEffectStateMachine.MARKER_SKIP_COUNT_KEY;
import static io.temporal.internal.statemachines.MutableSideEffectStateMachine.MUTABLE_SIDE_EFFECT_MARKER_NAME;
import static io.temporal.internal.statemachines.TestHistoryBuilder.assertCommand;
import static org.junit.Assert.assertEquals;
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
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import java.util.ArrayList;
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
      CommandsGeneratePlantUMLStateDiagrams.writeToFile(
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
                        "id1", (p) -> converter.toPayloads("result1"), c))
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
                (v, c) -> stateMachines.mutableSideEffect("id1", (p) -> Optional.empty(), c))
            .<Optional<Payloads>>add1(
                (v, c) -> stateMachines.mutableSideEffect("id1", (p) -> Optional.empty(), c))
            .<Optional<Payloads>>add1(
                (v, c) ->
                    stateMachines.mutableSideEffect(
                        "id1", (p) -> converter.toPayloads("result1"), c))
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
  public void testRecordAcrossMultipleWorkflowTasks() {
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Optional<Payloads>>add1(
                (v, c) ->
                    stateMachines.mutableSideEffect(
                        "id1", (p) -> converter.toPayloads("result1"), c))
            .<Optional<Payloads>>add1(
                (v, c) -> stateMachines.mutableSideEffect("id1", (p) -> Optional.empty(), c))
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
                (v, c) -> stateMachines.mutableSideEffect("id1", (p) -> Optional.empty(), c))
            .<Optional<Payloads>>add1(
                (v, c) ->
                    stateMachines.mutableSideEffect(
                        "id1", (p) -> converter.toPayloads("result2"), c))
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
