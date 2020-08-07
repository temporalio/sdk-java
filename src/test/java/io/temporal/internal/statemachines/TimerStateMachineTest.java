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

import static io.temporal.internal.statemachines.TestHistoryBuilder.assertCommand;
import static org.junit.Assert.assertEquals;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.TimerFiredEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class TimerStateMachineTest {

  private final DataConverter converter = DataConverter.getDefaultInstance();
  private WorkflowStateMachines manager;

  @Test
  public void testTimerFire() {
    class TestTimerFireListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<HistoryEvent>add1(
                (v, c) ->
                    manager.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setTimerId("timer1")
                            .setStartToFireTimeout(
                                ProtobufTimeUtils.ToProtoDuration(Duration.ofHours(1)))
                            .build(),
                        c))
            .add((firedEvent) -> manager.newCompleteWorkflow(Optional.empty()));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_TIMER_STARTED
        6: EVENT_TYPE_TIMER_FIRED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestTimerFireListener();
      manager = new WorkflowStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long timerStartedEventId = h.addGetEventId(EventType.EVENT_TYPE_TIMER_STARTED);
      h.add(
          EventType.EVENT_TYPE_TIMER_FIRED,
          TimerFiredEventAttributes.newBuilder()
              .setStartedEventId(timerStartedEventId)
              .setTimerId("timer1"));
      h.addWorkflowTaskScheduledAndStarted();
      assertEquals(2, h.getWorkflowTaskCount());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 1);
      assertCommand(CommandType.COMMAND_TYPE_START_TIMER, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 2);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      TestEntityManagerListenerBase listener = new TestTimerFireListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 2);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  private String payloadToString(Payloads payloads) {
    return converter.fromPayloads(0, Optional.of(payloads), String.class, String.class);
  }

  @Test
  public void testImmediateTimerCancellation() {
    class TestTimerImmediateCancellationListener extends TestEntityManagerListenerBase {
      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<HistoryEvent>add1(
                (v, c) ->
                    cancellationHandler =
                        manager.newTimer(
                            StartTimerCommandAttributes.newBuilder()
                                .setTimerId("timer1")
                                .setStartToFireTimeout(
                                    ProtobufTimeUtils.ToProtoDuration(Duration.ofHours(1)))
                                .build(),
                            c))
            .add(
                (firedEvent) ->
                    assertEquals(EventType.EVENT_TYPE_TIMER_CANCELED, firedEvent.getEventType()));
        builder
            .<HistoryEvent>add1(
                (v, c) ->
                    manager.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setTimerId("timer2")
                            .setStartToFireTimeout(
                                ProtobufTimeUtils.ToProtoDuration(Duration.ofHours(1)))
                            .build(),
                        c))
            .add((firedEvent) -> manager.newCompleteWorkflow(converter.toPayloads("result1")));

        // Immediate cancellation
        builder.add((v) -> cancellationHandler.apply());
      }
    }

    TestHistoryBuilder h = new TestHistoryBuilder();
    h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
    h.addWorkflowTask();
    long timerStartedEventId = h.addGetEventId(EventType.EVENT_TYPE_TIMER_STARTED);
    h.add(EventType.EVENT_TYPE_TIMER_FIRED, timerStartedEventId);
    h.addWorkflowTaskScheduledAndStarted();
    {
      TestEntityManagerListenerBase listener = new TestTimerImmediateCancellationListener();
      manager = new WorkflowStateMachines(listener);
      {
        List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 1);
        assertCommand(CommandType.COMMAND_TYPE_START_TIMER, commands);
        assertEquals("timer2", commands.get(0).getStartTimerCommandAttributes().getTimerId());
      }
      {
        List<Command> commands = h.handleWorkflowTaskTakeCommands(manager);
        assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
      }
    }
    {
      TestEntityManagerListenerBase listener = new TestTimerImmediateCancellationListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testStartedTimerCancellation() {

    class TestTimerCancellationListener extends TestEntityManagerListenerBase {
      private Functions.Proc cancellationHandler;
      private String firedTimerId;

      public String getFiredTimerId() {
        return firedTimerId;
      }

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<HistoryEvent>add1(
                (v, c) ->
                    cancellationHandler =
                        manager.newTimer(
                            StartTimerCommandAttributes.newBuilder()
                                .setTimerId("timer1")
                                .setStartToFireTimeout(
                                    ProtobufTimeUtils.ToProtoDuration(Duration.ofHours(1)))
                                .build(),
                            c))
            .add(
                (firedEvent) -> {
                  assertEquals(EventType.EVENT_TYPE_TIMER_CANCELED, firedEvent.getEventType());
                  manager.newCompleteWorkflow(converter.toPayloads("result1"));
                });
        builder
            .<HistoryEvent>add1(
                (v, c) ->
                    manager.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setTimerId("timer2")
                            .setStartToFireTimeout(
                                ProtobufTimeUtils.ToProtoDuration(Duration.ofHours(1)))
                            .build(),
                        c))
            .add(
                (firedEvent) -> {
                  assertEquals(EventType.EVENT_TYPE_TIMER_FIRED, firedEvent.getEventType());
                  firedTimerId = firedEvent.getTimerFiredEventAttributes().getTimerId();
                });
      }

      @Override
      protected void signal(HistoryEvent signalEvent, AsyncWorkflowBuilder<Void> builder) {
        assertEquals(
            "signal1", signalEvent.getWorkflowExecutionSignaledEventAttributes().getSignalName());
        builder.add((v) -> cancellationHandler.apply());
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_TIMER_STARTED
        6: EVENT_TYPE_TIMER_STARTED
        7: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        8: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        9: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        10: EVENT_TYPE_WORKFLOW_TASK_STARTED
        11: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        12: EVENT_TYPE_TIMER_CANCELED
        13: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
    h.addWorkflowTask();
    long timerStartedEventId1 = h.addGetEventId(EventType.EVENT_TYPE_TIMER_STARTED);
    long timerStartedEventId2 = h.addGetEventId(EventType.EVENT_TYPE_TIMER_STARTED);
    h.add(
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
            WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"))
        .addWorkflowTaskScheduled()
        .add(
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
            WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"))
        .addWorkflowTaskStarted()
        .addWorkflowTaskCompleted()
        .add(EventType.EVENT_TYPE_TIMER_CANCELED, timerStartedEventId1)
        .add(
            EventType.EVENT_TYPE_TIMER_FIRED,
            TimerFiredEventAttributes.newBuilder()
                .setStartedEventId(timerStartedEventId2)
                .setTimerId("timer2"))
        .addWorkflowTaskScheduledAndStarted();
    {
      TestTimerCancellationListener listener = new TestTimerCancellationListener();
      manager = new WorkflowStateMachines(listener);
      {
        List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 1);
        assertEquals(2, commands.size());
        assertEquals(CommandType.COMMAND_TYPE_START_TIMER, commands.get(0).getCommandType());
        assertEquals(CommandType.COMMAND_TYPE_START_TIMER, commands.get(1).getCommandType());
        assertEquals("timer1", commands.get(0).getStartTimerCommandAttributes().getTimerId());
        assertEquals("timer2", commands.get(1).getStartTimerCommandAttributes().getTimerId());
      }
      {
        List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 2);
        assertEquals(2, commands.size());
        assertEquals(CommandType.COMMAND_TYPE_CANCEL_TIMER, commands.get(0).getCommandType());
        assertEquals(
            CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
      }
    }
    {
      TestTimerCancellationListener listener = new TestTimerCancellationListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
      assertEquals("timer2", listener.getFiredTimerId());
    }
  }
}
