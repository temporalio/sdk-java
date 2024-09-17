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

import static io.temporal.internal.statemachines.MutableSideEffectStateMachine.*;
import static io.temporal.internal.statemachines.SideEffectStateMachine.SIDE_EFFECT_MARKER_NAME;
import static org.junit.Assert.*;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.*;
import io.temporal.api.protocol.v1.Message;
import io.temporal.api.update.v1.*;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.UpdateMessage;
import java.time.Duration;
import java.util.*;
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
  public void testUpdateAccept() throws InvalidProtocolBufferException {
    class TestUpdateListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {}

      @Override
      protected void update(UpdateMessage message, AsyncWorkflowBuilder<Void> builder) {
        builder
            .add(
                (r) -> {
                  if (message.getMessage().getId().startsWith("reject")) {
                    message
                        .getCallbacks()
                        .reject(converter.exceptionToFailure(new RuntimeException()));
                    return;
                  }
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
                        null,
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
        5: EVENT_TYPE_WORKFLOW_EXECUTION_ACCEPTED
        6: EVENT_TYPE_TIMER_STARTED
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
      long timerStartedEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_TIMER_STARTED,
              TimerStartedEventAttributes.newBuilder().setTimerId("timer1"));
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
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    }
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      Request request =
          Request.newBuilder()
              .setInput(
                  Input.newBuilder()
                      .setName("updateName")
                      .setArgs(converter.toPayloads("arg").get()))
              .build();
      stateMachines.setMessages(
          Collections.unmodifiableList(
              Arrays.asList(
                  new Message[] {
                    Message.newBuilder()
                        .setProtocolInstanceId("protocol_id")
                        .setId("id")
                        .setEventId(0)
                        .setBody(Any.pack(request))
                        .build(),
                  })));
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertEquals(2, commands.size());
      List<Message> messages = stateMachines.takeMessages();
      assertEquals(1, messages.size());
      Acceptance acceptance = messages.get(0).getBody().unpack(Acceptance.class);
      assertNotNull(acceptance);
      assertEquals(request, acceptance.getAcceptedRequest());
    }
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertEquals(0, commands.size());
    }
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertEquals(CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE, commands.get(0).getCommandType());
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertEquals(0, commands.size());
    }
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      stateMachines.setMessages(
          Collections.unmodifiableList(
              Arrays.asList(
                  Message.newBuilder()
                      .setProtocolInstanceId("reject_update_id")
                      .setId("reject")
                      .setEventId(9)
                      .setBody(
                          Any.pack(
                              Request.newBuilder()
                                  .setInput(Input.newBuilder().setName("updateName").build())
                                  .build()))
                      .build())));
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1, 2);
      assertEquals(CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE, commands.get(0).getCommandType());
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
    }
  }

  @Test
  public void testUpdateCompleted() {
    class TestUpdateListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder.add(
            (r) -> {
              stateMachines.setMessages(
                  Collections.unmodifiableList(
                      Arrays.asList(
                          Message.newBuilder()
                              .setProtocolInstanceId("protocol_id")
                              .setId("id")
                              .setEventId(2)
                              .setBody(
                                  Any.pack(
                                      Request.newBuilder()
                                          .setInput(
                                              Input.newBuilder().setName("updateName").build())
                                          .build()))
                              .build())));
            });
      }

      @Override
      protected void update(UpdateMessage message, AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Optional<Payloads>>add1(
                (v, c) -> {
                  message.getCallbacks().accept();
                  stateMachines.mutableSideEffect("id1", (p) -> converter.toPayloads("result1"), c);
                })
            .add(
                (r) -> {
                  message.getCallbacks().complete(converter.toPayloads("update result"), null);
                });
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        6: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    MarkerRecordedEventAttributes.Builder markerBuilder =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(MUTABLE_SIDE_EFFECT_MARKER_NAME)
            .putDetails(MARKER_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      h.addWorkflowTaskScheduled();
      h.addWorkflowTaskStarted();
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      h.handleWorkflowTask(stateMachines, 0, 2);
      List<ExecuteLocalActivityParameters> requests = stateMachines.takeLocalActivityRequests();
    }
  }

  @Test
  public void testUpdateCompletedImmediately() {
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
        5: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED
        6: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED
        7: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */

    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED,
          WorkflowExecutionUpdateAcceptedEventAttributes.newBuilder()
              .setProtocolInstanceId("protocol_id")
              .setAcceptedRequestMessageId("id")
              .setAcceptedRequestSequencingEventId(2)
              .setAcceptedRequest(
                  Request.newBuilder()
                      .setInput(
                          Input.newBuilder()
                              .setName("updateName")
                              .setArgs(converter.toPayloads("arg").get()))));
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED,
          WorkflowExecutionUpdateCompletedEventAttributes.newBuilder()
              .setOutcome(
                  Outcome.newBuilder().setSuccess(converter.toPayloads("m2Arg1").get()).build()));
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    }
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 0);
      assertEquals(0, commands.size());
    }
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 0, 1);
      assertEquals(0, commands.size());
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertEquals(0, commands.size());
    }
  }

  @Test
  public void testUpdateRejected() throws InvalidProtocolBufferException {
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
      Request request =
          Request.newBuilder()
              .setInput(
                  Input.newBuilder()
                      .setName("updateName")
                      .setArgs(converter.toPayloads("arg").get()))
              .build();
      stateMachines.setMessages(
          Collections.unmodifiableList(
              Arrays.asList(
                  new Message[] {
                    Message.newBuilder()
                        .setProtocolInstanceId("protocol_id")
                        .setId("id")
                        .setEventId(0)
                        .setBody(Any.pack(request))
                        .build(),
                  })));
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertEquals(0, commands.size());
      List<Message> messages = stateMachines.takeMessages();
      assertEquals(1, messages.size());
      Rejection rejection = messages.get(0).getBody().unpack(Rejection.class);
      assertNotNull(rejection);
      assertEquals(request, rejection.getRejectedRequest());
    }
  }

  @Test
  public void testUpdateRejectedAndReset() throws InvalidProtocolBufferException {
    class TestUpdateListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder.<HistoryEvent>add1(
            (v, c) ->
                stateMachines.newTimer(
                    StartTimerCommandAttributes.newBuilder()
                        .setTimerId("timer1")
                        .setStartToFireTimeout(
                            ProtobufTimeUtils.toProtoDuration(Duration.ofHours(1)))
                        .build(),
                    null,
                    c));
      }

      @Override
      protected void update(UpdateMessage message, AsyncWorkflowBuilder<Void> builder) {
        builder.add(
            (r) -> {
              message
                  .getCallbacks()
                  .reject(converter.exceptionToFailure(new RuntimeException("test failure")));
            });
      }

      @Override
      protected void signal(HistoryEvent signalEvent, AsyncWorkflowBuilder<Void> builder) {
        builder.<HistoryEvent>add1(
            (v, c) ->
                stateMachines.newTimer(
                    StartTimerCommandAttributes.newBuilder()
                        .setTimerId("timer2")
                        .setStartToFireTimeout(
                            ProtobufTimeUtils.toProtoDuration(Duration.ofHours(1)))
                        .build(),
                    null,
                    c));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_TIMER_STARTED
        6: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        7: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */

    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_TIMER_STARTED,
          TimerStartedEventAttributes.newBuilder().setTimerId("timer1"));
      h.addWorkflowTaskScheduled();
      h.addWorkflowTaskStarted();
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      Request request =
          Request.newBuilder()
              .setInput(
                  Input.newBuilder()
                      .setName("updateName")
                      .setArgs(converter.toPayloads("arg").get()))
              .build();
      stateMachines.setMessages(
          Collections.unmodifiableList(
              Arrays.asList(
                  new Message[] {
                    Message.newBuilder()
                        .setProtocolInstanceId("protocol_id")
                        .setId("id")
                        .setEventId(6)
                        .setBody(Any.pack(request))
                        .build(),
                  })));
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertEquals(0, commands.size());
      List<Message> messages = stateMachines.takeMessages();
      assertEquals(1, messages.size());
      Rejection rejection = messages.get(0).getBody().unpack(Rejection.class);
      assertNotNull(rejection);
      assertEquals(request, rejection.getRejectedRequest());
      // Simulate the server request to reset the workflow event ID
      stateMachines.resetStartedEvenId(3);
      // Create a new history after the reset event ID
      /*
          1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
          2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
          3: EVENT_TYPE_WORKFLOW_TASK_STARTED
          4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
          5: EVENT_TYPE_TIMER_STARTED
          6: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
          7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
          8: EVENT_TYPE_WORKFLOW_TASK_STARTED
      */
      TestHistoryBuilder historyAfterReset = new TestHistoryBuilder();
      historyAfterReset.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      historyAfterReset.addWorkflowTask();
      historyAfterReset.add(
          EventType.EVENT_TYPE_TIMER_STARTED,
          TimerStartedEventAttributes.newBuilder().setTimerId("timer1"));
      historyAfterReset.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
          WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"));
      historyAfterReset.addWorkflowTaskScheduled();
      historyAfterReset.addWorkflowTaskStarted();
      // Test new history with the old workflow state machines
      commands = historyAfterReset.handleWorkflowTaskTakeCommands(stateMachines, 1, 2);
      assertEquals(1, commands.size());
      messages = stateMachines.takeMessages();
      assertEquals(0, messages.size());
    }
  }

  @Test
  public void testUpdateAdmittedAndCompletedImmediately() {
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
            .add(
                (r) -> {
                  message.getCallbacks().complete(converter.toPayloads("update result"), null);
                })
            .add((r) -> stateMachines.completeWorkflow(Optional.empty()));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED
        3: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        4: EVENT_TYPE_WORKFLOW_TASK_STARTED
        5: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        6: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED
        7: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED
        8: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */

    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED,
          WorkflowExecutionUpdateAdmittedEventAttributes.newBuilder()
              .setRequest(
                  Request.newBuilder()
                      .setMeta(Meta.newBuilder().setUpdateId("protocol_id").build())
                      .setInput(
                          Input.newBuilder()
                              .setName("updateName")
                              .setArgs(converter.toPayloads("arg").get()))));
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED,
          WorkflowExecutionUpdateAcceptedEventAttributes.newBuilder()
              .setProtocolInstanceId("protocol_id")
              .setAcceptedRequestMessageId("id")
              .setAcceptedRequestSequencingEventId(2));
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED,
          WorkflowExecutionUpdateCompletedEventAttributes.newBuilder()
              .setOutcome(
                  Outcome.newBuilder().setSuccess(converter.toPayloads("m2Arg1").get()).build()));
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    }
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 0);
      assertEquals(0, commands.size());
    }
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 0, 1);
      assertEquals(3, commands.size());
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertEquals(0, commands.size());
    }
  }

  @Test
  public void testUpdateAdmittedAndMessage() {
    // Test a mix of update accepted events and messages
    class TestUpdateListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder.add(
            (r) -> {
              stateMachines.setMessages(
                  Collections.unmodifiableList(
                      Arrays.asList(
                          Message.newBuilder()
                              .setProtocolInstanceId("message_updateID")
                              .setId("message_updateID/request")
                              .setEventId(6)
                              .setBody(
                                  Any.pack(
                                      Request.newBuilder()
                                          .setInput(
                                              Input.newBuilder().setName("updateName").build())
                                          .build()))
                              .build())));
            });
      }

      @Override
      protected void update(UpdateMessage message, AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Optional<Payloads>>add1(
                (v, c) -> {
                  message.getCallbacks().accept();
                  stateMachines.mutableSideEffect("id1", (p) -> converter.toPayloads("result1"), c);
                })
            .add(
                (r) -> {
                  message.getCallbacks().complete(converter.toPayloads("update result"), null);
                });
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED
        6: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        7: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED,
          WorkflowExecutionUpdateAdmittedEventAttributes.newBuilder()
              .setRequest(
                  Request.newBuilder()
                      .setMeta(Meta.newBuilder().setUpdateId("admitted_updateID").build())
                      .setInput(
                          Input.newBuilder()
                              .setName("updateName")
                              .setArgs(converter.toPayloads("arg").get()))));
      h.addWorkflowTaskScheduled();
      h.addWorkflowTaskStarted();
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1, 2);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 0, 2);
      assertEquals(6, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE, commands.get(0).getCommandType());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(1).getCommandType());
      assertEquals(CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE, commands.get(2).getCommandType());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(3).getCommandType());
      assertEquals(CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE, commands.get(4).getCommandType());
      assertEquals(CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE, commands.get(5).getCommandType());
      List<Message> messages = stateMachines.takeMessages();
      assertEquals(4, messages.size());
    }
  }

  @Test
  public void testUpdateAdmittedButRejected() {
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
        2: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED
        3: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        4: EVENT_TYPE_WORKFLOW_TASK_STARTED
        5: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        6: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        7: EVENT_TYPE_WORKFLOW_TASK_STARTED
        8: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
    */

    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED,
          WorkflowExecutionUpdateAdmittedEventAttributes.newBuilder()
              .setRequest(
                  Request.newBuilder()
                      .setMeta(Meta.newBuilder().setUpdateId("admitted_updateID").build())
                      .setInput(
                          Input.newBuilder()
                              .setName("updateName")
                              .setArgs(converter.toPayloads("arg").get()))));
      h.addWorkflowTask();
      h.addWorkflowTask();
    }
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertEquals(0, commands.size());
      // Verify rejection message
      List<Message> messages = stateMachines.takeMessages();
      assertEquals(1, messages.size());
      assertEquals("admitted_updateID/request/reject", messages.get(0).getId());
      assertEquals(
          "type.googleapis.com/temporal.api.update.v1.Rejection",
          messages.get(0).getBody().getTypeUrl());
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1, 2);
      assertEquals(0, commands.size());
      assertEquals(0, stateMachines.takeMessages().size());
    }
  }

  @Test
  public void testUpdateAdmittedReplay() {
    class TestUpdateListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {}

      @Override
      protected void update(UpdateMessage message, AsyncWorkflowBuilder<Void> builder) {
        builder
            .add(
                (r) -> {
                  assertEquals(
                      message.getMessage().getBody().getTypeUrl(),
                      "type.googleapis.com/temporal.api.update.v1.Request");
                  Request update = null;
                  try {
                    update = message.getMessage().getBody().unpack(Request.class);
                  } catch (InvalidProtocolBufferException e) {
                    assertTrue(false);
                  }
                  assertEquals("updateName", update.getInput().getName());
                  message.getCallbacks().accept();
                })
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
        5: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED
        6: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        7: EVENT_TYPE_WORKFLOW_TASK_STARTED
        8: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        9: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED
        10: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED
        11: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */

    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED,
          WorkflowExecutionUpdateAdmittedEventAttributes.newBuilder()
              .setRequest(
                  Request.newBuilder()
                      .setMeta(Meta.newBuilder().setUpdateId("protocol_id").build())
                      .setInput(
                          Input.newBuilder()
                              .setName("updateName")
                              .setArgs(converter.toPayloads("arg").get()))));
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED,
          WorkflowExecutionUpdateAcceptedEventAttributes.newBuilder()
              .setProtocolInstanceId("protocol_id")
              .setAcceptedRequestMessageId("id")
              .setAcceptedRequestSequencingEventId(5));
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED,
          WorkflowExecutionUpdateCompletedEventAttributes.newBuilder()
              .setOutcome(
                  Outcome.newBuilder().setSuccess(converter.toPayloads("m2Arg1").get()).build()));
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    }
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertEquals(3, commands.size());
    }
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertEquals(0, commands.size());
    }
  }

  @Test
  public void testUpdateSignalSandwichAdmittedMessage() {
    class TestUpdateListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {}

      @Override
      protected void signal(HistoryEvent signalEvent, AsyncWorkflowBuilder<Void> builder) {
        assertEquals(
            "signal1", signalEvent.getWorkflowExecutionSignaledEventAttributes().getSignalName());
        builder.<Optional<Payloads>>add1(
            (r, c) -> {
              stateMachines.sideEffect(() -> converter.toPayloads("m2Arg1"), c);
            });
      }

      @Override
      protected void update(UpdateMessage message, AsyncWorkflowBuilder<Void> builder) {
        builder
            .add(
                (r) -> {
                  message.getCallbacks().accept();
                })
            .add(
                (r) -> {
                  message.getCallbacks().complete(converter.toPayloads("update result"), null);
                });
        if (message.getMessage().getProtocolInstanceId() == "message_update") {
          builder.add((r) -> stateMachines.completeWorkflow(Optional.empty()));
        }
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED
        6: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        7: EVENT_TYPE_WORKFLOW_TASK_STARTED
        8: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        9: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED
        10: EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED
        11: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */

    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED,
          WorkflowExecutionUpdateAdmittedEventAttributes.newBuilder()
              .setRequest(
                  Request.newBuilder()
                      .setMeta(Meta.newBuilder().setUpdateId("admitted_update").build())
                      .setInput(
                          Input.newBuilder()
                              .setName("updateName")
                              .setArgs(converter.toPayloads("arg").get()))));
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
          WorkflowExecutionSignaledEventAttributes.newBuilder()
              .setSignalName("signal1")
              .setInput(converter.toPayloads("signal1Arg").get()));
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED,
          WorkflowExecutionUpdateAcceptedEventAttributes.newBuilder()
              .setProtocolInstanceId("admitted_update")
              .setAcceptedRequestMessageId("admitted_update/request/accept")
              .setAcceptedRequestSequencingEventId(5));
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED,
          WorkflowExecutionUpdateCompletedEventAttributes.newBuilder()
              .setOutcome(
                  Outcome.newBuilder().setSuccess(converter.toPayloads("m2Arg1").get()).build()));
      h.add(
          EventType.EVENT_TYPE_MARKER_RECORDED,
          MarkerRecordedEventAttributes.newBuilder()
              .setMarkerName(SIDE_EFFECT_MARKER_NAME)
              .putDetails(MARKER_DATA_KEY, converter.toPayloads("m2Arg1").get())
              .build());
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED,
          WorkflowExecutionUpdateAcceptedEventAttributes.newBuilder()
              .setProtocolInstanceId("message_update")
              .setAcceptedRequestMessageId("message_update/request/accept")
              .setAcceptedRequestSequencingEventId(7)
              .setAcceptedRequest(
                  Request.newBuilder()
                      .setInput(
                          Input.newBuilder()
                              .setName("updateName")
                              .setArgs(converter.toPayloads("arg").get()))));
      h.add(
          EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED,
          WorkflowExecutionUpdateCompletedEventAttributes.newBuilder()
              .setOutcome(
                  Outcome.newBuilder().setSuccess(converter.toPayloads("m2Arg1").get()).build()));
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    }

    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      stateMachines.setMessages(
          Collections.unmodifiableList(
              Arrays.asList(
                  Message.newBuilder()
                      .setProtocolInstanceId("message_update")
                      .setId("message_update/request")
                      .setEventId(7)
                      .setBody(
                          Any.pack(
                              Request.newBuilder()
                                  .setInput(Input.newBuilder().setName("updateName").build())
                                  .build()))
                      .build())));
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1, 2);
      assertEquals(6, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE, commands.get(0).getCommandType());
      assertEquals(
          "admitted_update/request/accept",
          commands.get(0).getProtocolMessageCommandAttributes().getMessageId());
      assertEquals(CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE, commands.get(1).getCommandType());
      assertEquals(
          "admitted_update/request/complete",
          commands.get(1).getProtocolMessageCommandAttributes().getMessageId());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(2).getCommandType());
      assertEquals(CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE, commands.get(3).getCommandType());
      assertEquals(
          "message_update/request/accept",
          commands.get(3).getProtocolMessageCommandAttributes().getMessageId());
      assertEquals(CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE, commands.get(4).getCommandType());
      assertEquals(
          "message_update/request/complete",
          commands.get(4).getProtocolMessageCommandAttributes().getMessageId());
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(5).getCommandType());
      List<Message> messages = stateMachines.takeMessages();
      assertEquals(4, messages.size());
    }
    {
      TestEntityManagerListenerBase listener = new TestUpdateListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertEquals(0, commands.size());
    }
  }
}
