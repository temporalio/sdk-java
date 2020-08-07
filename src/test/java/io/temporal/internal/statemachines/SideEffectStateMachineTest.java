/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.statemachines;

import static io.temporal.internal.statemachines.MutableSideEffectStateMachine.MARKER_DATA_KEY;
import static io.temporal.internal.statemachines.SideEffectStateMachine.SIDE_EFFECT_MARKER_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.api.command.v1.Command;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.common.converter.DataConverter;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class SideEffectStateMachineTest {

  private final DataConverter converter = DataConverter.getDefaultInstance();
  private WorkflowStateMachines manager;

  @Test
  public void testSideEffectStateMachine() {
    class TestListener extends TestEntityManagerListenerBase {
      Optional<Payloads> result;

      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Optional<Payloads>>add1(
                (v, c) -> manager.sideEffect(() -> converter.toPayloads("m1Arg1", "m1Arg2"), c))
            .<Optional<Payloads>>add1(
                (r, c) -> {
                  result = r;
                  manager.sideEffect(() -> converter.toPayloads("m2Arg1"), c);
                })
            .add((r) -> manager.newCompleteWorkflow(Optional.empty()));
      }
    }

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
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_DATA_KEY, converter.toPayloads("m2Arg1").get())
                    .build())
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    {
      TestEntityManagerListenerBase listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 1);
      assertEquals(3, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(1).getCommandType());
      Optional<Payloads> data1 =
          Optional.of(
              commands
                  .get(0)
                  .getRecordMarkerCommandAttributes()
                  .getDetailsMap()
                  .get(MARKER_DATA_KEY));
      assertEquals("m1Arg1", converter.fromPayloads(0, data1, String.class, String.class));
      assertEquals("m1Arg2", converter.fromPayloads(1, data1, String.class, String.class));

      Optional<Payloads> data2 =
          Optional.of(
              commands
                  .get(1)
                  .getRecordMarkerCommandAttributes()
                  .getDetailsMap()
                  .get(MARKER_DATA_KEY));
      assertEquals("m2Arg1", converter.fromPayloads(0, data2, String.class, String.class));

      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(2).getCommandType());
    }
    {
      TestEntityManagerListenerBase listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager);
      assertTrue(commands.isEmpty());
    }
  }
}
