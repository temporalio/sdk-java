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

import static io.temporal.internal.statemachines.TestHistoryBuilder.assertCommand;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.google.protobuf.Any;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.*;
import io.temporal.api.protocol.v1.Message;
import io.temporal.api.update.v1.Input;
import io.temporal.api.update.v1.Outcome;
import io.temporal.api.update.v1.Request;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.UpdateMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.AfterClass;
import org.junit.Test;

public class UpdateProtocolStateMachineTest {
  private final DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;
  private WorkflowStateMachines stateMachines;

  private static final List<
          StateMachine<
              UpdateProtocolStateMachine.State,
              UpdateProtocolStateMachine.ExplicitEvent,
              UpdateProtocolStateMachine>>
      stateMachineList = new ArrayList<>();

  private WorkflowStateMachines newStateMachines(TestEntityManagerListenerBase listener) {
    return new WorkflowStateMachines(listener, stateMachineList::add);
  }

  @AfterClass
  public static void generateCoverage() {
    List<
            Transition<
                UpdateProtocolStateMachine.State,
                TransitionEvent<UpdateProtocolStateMachine.ExplicitEvent>>>
        missed =
            UpdateProtocolStateMachine.STATE_MACHINE_DEFINITION.getUnvisitedTransitions(
                stateMachineList);
    if (!missed.isEmpty()) {
      CommandsGeneratePlantUMLStateDiagrams.writeToFile(
          "test",
          VersionStateMachine.class,
          UpdateProtocolStateMachine.STATE_MACHINE_DEFINITION.asPlantUMLStateDiagramCoverage(
              stateMachineList));
      fail(
          "UpdateProtocolStateMachineTest is missing test coverage for the following transitions:\n"
              + missed);
    }
  }

  @Test
  public void testUpdateAccept() {
    class TestUpdateListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {}

      @Override
      protected void update(UpdateMessage message, AsyncWorkflowBuilder<Void> builder) {
        builder
            .add(
                (r) -> {
                  message.getCallbacks().accept();
                })
            .<HistoryEvent>add1(
                (v, c) ->
                    stateMachines.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setTimerId("timer1")
                            .setStartToFireTimeout(
                                ProtobufTimeUtils.toProtoDuration(Duration.ofHours(1)))
                            .build(),
                        c))
            .add(
                (r) -> {
                  message.getCallbacks().complete(converter.toPayloads("update result"), null);
                })
            .add((r) -> stateMachines.completeWorkflow(Optional.empty()));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_TIMER_STARTED
        6: EVENT_TYPE_WORKFLOW_EXECUTION_ACCEPTED
        7: EVENT_TYPE_TIMER_FIRED
        8: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        9: EVENT_TYPE_WORKFLOW_TASK_STARTED
        10: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
        11: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        12: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
    */

    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long timerStartedEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_TIMER_STARTED,
              TimerStartedEventAttributes.newBuilder().setTimerId("timer1"));
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED,
          WorkflowExecutionUpdateAcceptedEventAttributes.newBuilder()
              .setProtocolInstanceId("protocol_id")
              .setAcceptedRequestMessageId("id")
              .setAcceptedRequestSequencingEventId(0)
              .setAcceptedRequest(
                  Request.newBuilder()
                      .setInput(
                          Input.newBuilder()
                              .setName("updateName")
                              .setArgs(converter.toPayloads("arg").get()))));
      h.add(
          EventType.EVENT_TYPE_TIMER_FIRED,
          TimerFiredEventAttributes.newBuilder()
              .setStartedEventId(timerStartedEventId)
              .setTimerId("timer1"));
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED,
          WorkflowExecutionUpdateCompletedEventAttributes.newBuilder()
              .setOutcome(
                  Outcome.newBuilder().setSuccess(converter.toPayloads("m2Arg1").get()).build()));
      h.addWorkflowTask();
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testUpdateRejected() {
    class TestUpdateListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {}

      @Override
      protected void update(UpdateMessage message, AsyncWorkflowBuilder<Void> builder) {
        builder.add(
            (r) -> {
              message
                  .getCallbacks()
                  .reject(converter.exceptionToFailure(new RuntimeException("test failure")));
            });
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
    */

    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      Any messageBody =
          Any.pack(
              Request.newBuilder()
                  .setInput(
                      Input.newBuilder()
                          .setName("updateName")
                          .setArgs(converter.toPayloads("arg").get()))
                  .build());
      stateMachines.setMessages(
          Arrays.asList(
              new Message[] {
                Message.newBuilder()
                    .setProtocolInstanceId("protocol_id")
                    .setId("id")
                    .setEventId(0)
                    .setBody(messageBody)
                    .build(),
              }));
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertEquals(0, commands.size());
    }
  }
}
