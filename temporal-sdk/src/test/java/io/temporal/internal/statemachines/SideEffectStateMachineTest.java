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
import static io.temporal.internal.statemachines.SideEffectStateMachine.SIDE_EFFECT_MARKER_NAME;
import static io.temporal.internal.statemachines.TestHistoryBuilder.assertCommand;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.api.command.v1.Command;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.AfterClass;
import org.junit.Test;

public class SideEffectStateMachineTest {

  private final DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;
  private WorkflowStateMachines stateMachines;

  private static final List<
          StateMachine<
              SideEffectStateMachine.State,
              SideEffectStateMachine.ExplicitEvent,
              SideEffectStateMachine>>
      stateMachineList = new ArrayList<>();

  private WorkflowStateMachines newStateMachines(TestEntityManagerListenerBase listener) {
    return new WorkflowStateMachines(listener, (stateMachineList::add));
  }

  @AfterClass
  public static void generateCoverage() {
    List<
            Transition<
                SideEffectStateMachine.State,
                TransitionEvent<SideEffectStateMachine.ExplicitEvent>>>
        missed =
            SideEffectStateMachine.STATE_MACHINE_DEFINITION.getUnvisitedTransitions(
                stateMachineList);
    if (!missed.isEmpty()) {
      CommandsGeneratePlantUMLStateDiagrams.writeToFile(
          "test",
          SideEffectStateMachine.class,
          SideEffectStateMachine.STATE_MACHINE_DEFINITION.asPlantUMLStateDiagramCoverage(
              stateMachineList));
      fail(
          "SideEffectStateMachine is missing test coverage for the following transitions:\n"
              + missed);
    }
  }

  @Test
  public void testSideEffectStateMachine() {
    class TestListener extends TestEntityManagerListenerBase {
      Optional<Payloads> result;

      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Optional<Payloads>>add1(
                (v, c) ->
                    stateMachines.sideEffect(() -> converter.toPayloads("m1Arg1", "m1Arg2"), c))
            .<Optional<Payloads>>add1((r, c) -> result = r);
      }

      @Override
      protected void signal(HistoryEvent signalEvent, AsyncWorkflowBuilder<Void> builder) {
        assertEquals(
            "signal1", signalEvent.getWorkflowExecutionSignaledEventAttributes().getSignalName());
        builder
            .<Optional<Payloads>>add1(
                (r, c) -> {
                  stateMachines.sideEffect(() -> converter.toPayloads("m2Arg1"), c);
                })
            .add((r) -> stateMachines.completeWorkflow(Optional.empty()));
      }
    }
    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_MARKER_RECORDED
        6: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        7: EVENT_TYPE_WORKFLOW_TASK_STARTED
        8: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        9: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        10: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        11: EVENT_TYPE_WORKFLOW_TASK_STARTED
        12: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        13: EVENT_TYPE_MARKER_RECORDED
        14: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */
    MarkerRecordedEventAttributes.Builder markerBuilder =
        MarkerRecordedEventAttributes.newBuilder().setMarkerName(SIDE_EFFECT_MARKER_NAME);
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_DATA_KEY, converter.toPayloads("m1Arg1", "m1Arg2").get())
                    .build())
            .add(
                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
                WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"))
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_DATA_KEY, converter.toPayloads("m2Arg1").get())
                    .build())
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    {
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_RECORD_MARKER, commands);
      Optional<Payloads> data1 =
          Optional.of(
              commands
                  .get(0)
                  .getRecordMarkerCommandAttributes()
                  .getDetailsMap()
                  .get(MARKER_DATA_KEY));
      assertEquals("m1Arg1", converter.fromPayloads(0, data1, String.class, String.class));
      assertEquals("m1Arg2", converter.fromPayloads(1, data1, String.class, String.class));
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      Optional<Payloads> data2 =
          Optional.of(
              commands
                  .get(0)
                  .getRecordMarkerCommandAttributes()
                  .getDetailsMap()
                  .get(MARKER_DATA_KEY));
      assertEquals("m2Arg1", converter.fromPayloads(0, data2, String.class, String.class));
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
    }
    {
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertTrue(commands.isEmpty());
    }
  }
}
