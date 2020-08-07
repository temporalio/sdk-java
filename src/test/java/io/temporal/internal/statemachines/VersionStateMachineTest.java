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
import static io.temporal.internal.statemachines.VersionStateMachine.*;
import static io.temporal.workflow.Workflow.DEFAULT_VERSION;
import static org.junit.Assert.*;

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
import io.temporal.internal.replay.NonDeterministicWorkflowError;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class VersionStateMachineTest {

  private final DataConverter converter = DataConverter.getDefaultInstance();
  private WorkflowStateMachines manager;

  @Test
  public void testOne() {
    final int maxSupported = 12;
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer>add1((v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .add((v) -> manager.newCompleteWorkflow(converter.toPayloads(v)));
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
            .setMarkerName(VERSION_MARKER_NAME)
            .putDetails(MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_VERSION_KEY, converter.toPayloads(maxSupported).get())
                    .build())
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    {
      TestEntityManagerListenerBase listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 1);

      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
      Optional<Payloads> resultData =
          Optional.of(commands.get(1).getCompleteWorkflowExecutionCommandAttributes().getResult());
      assertEquals(
          maxSupported, (int) converter.fromPayloads(0, resultData, Integer.class, Integer.class));
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager);
      assertTrue(commands.toString(), commands.isEmpty());
    }
  }

  @Test
  public void testMultiple() {
    final int maxSupported = 13;
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer>add1((v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .<Integer>add1(
                (v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported + 10, c))
            .<Integer>add1(
                (v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported + 100, c))
            .add((v) -> manager.newCompleteWorkflow(converter.toPayloads(v)));
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
            .setMarkerName(VERSION_MARKER_NAME)
            .putDetails(MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_VERSION_KEY, converter.toPayloads(maxSupported).get())
                    .build())
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    {
      TestEntityManagerListenerBase listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 1);

      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      int version =
          converter.fromPayloads(
              0,
              Optional.ofNullable(
                  commands
                      .get(0)
                      .getRecordMarkerCommandAttributes()
                      .getDetailsOrThrow(MARKER_VERSION_KEY)),
              Integer.class,
              Integer.class);
      assertEquals(maxSupported, version);
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
      Optional<Payloads> resultData =
          Optional.of(commands.get(1).getCompleteWorkflowExecutionCommandAttributes().getResult());
      assertEquals(
          maxSupported, (int) converter.fromPayloads(0, resultData, Integer.class, Integer.class));
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager);
      assertTrue(commands.isEmpty());
    }
  }

  @Test
  public void testUnsupportedVersion() {
    final int maxSupported = 13;
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer>add1((v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .<Integer>add1(
                (v, c) -> manager.getVersion("id1", maxSupported + 10, maxSupported + 10, c))
            .add((v) -> manager.newCompleteWorkflow(converter.toPayloads(v)));
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
            .setMarkerName(VERSION_MARKER_NAME)
            .putDetails(MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_VERSION_KEY, converter.toPayloads(maxSupported).get())
                    .build())
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    TestEntityManagerListenerBase listener = new TestListener();
    manager = new WorkflowStateMachines(listener);
    try {
      h.handleWorkflowTaskTakeCommands(manager);
      fail("failure expected");
    } catch (Throwable e) {
      assertTrue(
          e.getMessage()
              .startsWith("Version " + maxSupported + " of changeId id1 is not supported"));
    }
  }

  /**
   * Tests that getVersion call returns DEFAULT version when there is no correspondent marker in the
   * history. It happens when getVersion call was added at the workflow place that already executed.
   */
  @Test
  public void testNewGetVersion() {
    final int maxSupported = 13;
    class TestListener extends TestEntityManagerListenerBase {
      final StringBuilder trace = new StringBuilder();

      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer>add1((v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .<Integer>add1(
                (v, c) -> {
                  trace.append(v + ", ");
                  manager.getVersion("id1", DEFAULT_VERSION, maxSupported + 10, c);
                })
            .<Integer>add1(
                (v, c) -> {
                  trace.append(v + ", ");
                  manager.getVersion("id1", DEFAULT_VERSION, maxSupported + 100, c);
                })
            .<HistoryEvent>add1(
                (v, c) -> {
                  trace.append(v);
                  manager.newTimer(
                      StartTimerCommandAttributes.newBuilder()
                          .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                          .build(),
                      c);
                })
            .add((v) -> manager.newCompleteWorkflow(converter.toPayloads(v)));
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
      9: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
      10: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long timerStartedEventId1 = h.addGetEventId(EventType.EVENT_TYPE_TIMER_STARTED);
    h.add(
            EventType.EVENT_TYPE_TIMER_FIRED,
            TimerFiredEventAttributes.newBuilder()
                .setStartedEventId(timerStartedEventId1)
                .setTimerId("timer1"))
        .addWorkflowTask()
        .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    {
      // Full replay
      TestListener listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager);
      assertTrue(commands.isEmpty());
      assertEquals(
          DEFAULT_VERSION + ", " + DEFAULT_VERSION + ", " + DEFAULT_VERSION,
          listener.trace.toString());
    }
  }

  /**
   * Tests that getVersion call returns DEFAULT version when there is no correspondent marker in the
   * history. It happens when getVersion call was added at the workflow place that already executed.
   * This test is different from testNewGetVersion is having two workflow tasks in a raw without any
   * commands or events in between.
   */
  @Test
  public void testNewGetVersionNoCommand() {
    final int maxSupported = 13;
    class TestListener extends TestEntityManagerListenerBase {

      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer>add1((v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .add((v) -> manager.newCompleteWorkflow(converter.toPayloads(v)));
      }
    }
    /*
       1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
       2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
       3: EVENT_TYPE_WORKFLOW_TASK_STARTED
       4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
       5: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
       6: EVENT_TYPE_WORKFLOW_TASK_STARTED
       7: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
       8: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .addWorkflowTask()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    {
      // Full replay
      TestListener listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 2);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testRecordAcrossMultipleWorkflowTasks() {
    final int maxSupported = 133;
    class TestListener extends TestEntityManagerListenerBase {
      final StringBuilder trace = new StringBuilder();

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer>add1((v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .<Integer>add1(
                (v, c) -> {
                  trace.append(v + ", ");
                  manager.getVersion("id1", DEFAULT_VERSION, maxSupported + 10, c);
                })
            .<HistoryEvent>add1(
                (v, c) -> {
                  trace.append(v + ", ");
                  manager.newTimer(
                      StartTimerCommandAttributes.newBuilder()
                          .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                          .build(),
                      c);
                })
            .<HistoryEvent>add1(
                (v, c) ->
                    manager.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        c))
            .<Integer>add1(
                (v, c) -> manager.getVersion("id1", maxSupported - 3, maxSupported + 10, c))
            .<Integer>add1(
                (v, c) -> {
                  trace.append(v + ", ");
                  manager.getVersion("id1", DEFAULT_VERSION, maxSupported + 100, c);
                })
            .add(
                (v) -> {
                  trace.append(v);
                  manager.newCompleteWorkflow(converter.toPayloads(v));
                });
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
            .setMarkerName(VERSION_MARKER_NAME)
            .putDetails(MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_VERSION_KEY, converter.toPayloads(maxSupported).get())
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
        .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    {
      TestEntityManagerListenerBase listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 1);

      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      int version =
          converter.fromPayloads(
              0,
              Optional.ofNullable(
                  commands
                      .get(0)
                      .getRecordMarkerCommandAttributes()
                      .getDetailsOrThrow(MARKER_VERSION_KEY)),
              Integer.class,
              Integer.class);
      assertEquals(maxSupported, version);
      assertEquals(CommandType.COMMAND_TYPE_START_TIMER, commands.get(1).getCommandType());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 2);
      assertCommand(CommandType.COMMAND_TYPE_START_TIMER, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 3);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
      Optional<Payloads> resultData =
          Optional.of(commands.get(0).getCompleteWorkflowExecutionCommandAttributes().getResult());
      assertEquals(
          maxSupported, (int) converter.fromPayloads(0, resultData, Integer.class, Integer.class));
    }
    {
      // Full replay
      TestListener listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager);
      assertTrue(commands.isEmpty());
      assertEquals(
          maxSupported + ", " + maxSupported + ", " + maxSupported + ", " + maxSupported,
          listener.trace.toString());
    }
  }

  /**
   * Test that the correct versions are returned even after some GetVersion calls removals. Based on
   * {@link #testRecordAcrossMultipleWorkflowTasks()} with some getVersion calls removed.
   */
  @Test
  public void testGetVersionCallsRemoval() {
    final int maxSupported = 12654;
    class TestListener extends TestEntityManagerListenerBase {
      final StringBuilder trace = new StringBuilder();

      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            /*.<Integer>add((v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported, c))*/
            .<Integer>add1(
                (v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported + 10, c))
            .<HistoryEvent>add1(
                (v, c) -> {
                  trace.append(v + ", ");
                  manager.newTimer(
                      StartTimerCommandAttributes.newBuilder()
                          .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                          .build(),
                      c);
                })
            .<HistoryEvent>add1(
                (v, c) ->
                    manager.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        c))
            .<Integer>add1(
                (v, c) -> manager.getVersion("id1", maxSupported - 3, maxSupported + 10, c))
            /*.<Integer>add((v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported + 100, c));*/
            .add(
                (v) -> {
                  trace.append(v);
                  manager.newCompleteWorkflow(converter.toPayloads(v));
                });
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
            .setMarkerName(VERSION_MARKER_NAME)
            .putDetails(MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_VERSION_KEY, converter.toPayloads(maxSupported).get())
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
        .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    {
      // Full replay
      TestListener listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager);
      assertTrue(commands.isEmpty());
      assertEquals(maxSupported + ", " + maxSupported, listener.trace.toString());
    }
  }

  /**
   * Test that the correct versions are returned after some GetVersion calls with different ids are
   * removed.
   */
  @Test
  public void testGetVersionCallsDifferentIdRemoval() {
    final int maxSupported = 12654;
    class TestListener extends TestEntityManagerListenerBase {
      int versionId2;

      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer>add1(
                (v, c) ->
                    manager.getVersion(
                        "id2",
                        DEFAULT_VERSION,
                        maxSupported,
                        (r) -> {
                          versionId2 = r;
                          c.apply(r);
                        }))
            .<HistoryEvent>add1(
                (v, c) ->
                    manager.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        c))
            .add((v) -> manager.newCompleteWorkflow(converter.toPayloads(v)));
      }
    }
    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_MARKER_RECORDED id1
        6: EVENT_TYPE_MARKER_RECORDED id2
        7: EVENT_TYPE_MARKER_RECORDED id3
        8: EVENT_TYPE_TIMER_STARTED
        9: EVENT_TYPE_TIMER_FIRED
        10: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        11: EVENT_TYPE_WORKFLOW_TASK_STARTED
        12: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        13: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED}
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                MarkerRecordedEventAttributes.newBuilder()
                    .setMarkerName(VERSION_MARKER_NAME)
                    .putDetails(MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get())
                    .putDetails(MARKER_VERSION_KEY, converter.toPayloads(maxSupported + 10).get())
                    .build())
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                MarkerRecordedEventAttributes.newBuilder()
                    .setMarkerName(VERSION_MARKER_NAME)
                    .putDetails(MARKER_CHANGE_ID_KEY, converter.toPayloads("id2").get())
                    .putDetails(MARKER_VERSION_KEY, converter.toPayloads(maxSupported).get())
                    .build())
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                MarkerRecordedEventAttributes.newBuilder()
                    .setMarkerName(VERSION_MARKER_NAME)
                    .putDetails(MARKER_CHANGE_ID_KEY, converter.toPayloads("id3").get())
                    .putDetails(MARKER_VERSION_KEY, converter.toPayloads(maxSupported + 20).get())
                    .build());
    long timerStartedEventId1 = h.addGetEventId(EventType.EVENT_TYPE_TIMER_STARTED);
    h.add(
            EventType.EVENT_TYPE_TIMER_FIRED,
            TimerFiredEventAttributes.newBuilder()
                .setStartedEventId(timerStartedEventId1)
                .setTimerId("timer1"))
        .addWorkflowTask()
        .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    {
      // Full replay
      TestListener listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager);
      assertTrue(commands.isEmpty());
      assertEquals(maxSupported, listener.versionId2);
    }
  }

  /**
   * Test that the correct versions are returned even after some GetVersion calls removals. Based on
   * {@link #testRecordAcrossMultipleWorkflowTasks()} with some getVersion calls removed.
   */
  @Test
  public void testGetVersionCallsRemovalInNextWorkflowTask() {
    final int maxSupported = 12654;
    class TestListener extends TestEntityManagerListenerBase {
      final StringBuilder trace = new StringBuilder();

      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            /*
            .<Integer>add((v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
                .<Integer>add(
                        (v, c) -> {
                          trace.append(v + ", ");
                          manager.getVersion("id1", DEFAULT_VERSION, maxSupported + 10, c);
                        })
                 */
            .<HistoryEvent>add1(
                (v, c) ->
                    manager.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        c))
            .<HistoryEvent>add1(
                (v, c) ->
                    manager.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        c))
            /*.<Integer>add(
            (v, c) -> manager.getVersion("id1", maxSupported - 3, maxSupported + 10, c))*/
            .<Integer>add1(
                (v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported + 100, c))
            .add(
                (v) -> {
                  trace.append(v);
                  manager.newCompleteWorkflow(converter.toPayloads(v));
                });
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
            .setMarkerName(VERSION_MARKER_NAME)
            .putDetails(MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_VERSION_KEY, converter.toPayloads(maxSupported).get())
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
        .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    {
      TestEntityManagerListenerBase listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 2);
      assertCommand(CommandType.COMMAND_TYPE_START_TIMER, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager, 3);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
      Optional<Payloads> resultData =
          Optional.of(commands.get(0).getCompleteWorkflowExecutionCommandAttributes().getResult());
      assertEquals(
          maxSupported, (int) converter.fromPayloads(0, resultData, Integer.class, Integer.class));
    }
    {
      // Full replay
      TestListener listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(manager);
      assertTrue(commands.isEmpty());
      assertEquals(String.valueOf(maxSupported), listener.trace.toString());
    }
  }

  /** It is not allowed to add getVersion calls with existing changeId. */
  @Test
  public void testAddingGetVersionExistingIdFails() {
    final int maxSupported = 133;
    class TestListener extends TestEntityManagerListenerBase {

      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer>add1((v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .<HistoryEvent>add1(
                (v, c) ->
                    manager.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        c))
            .<Integer>add1(
                (v, c) -> manager.getVersion("id1", DEFAULT_VERSION, maxSupported + 100, c))
            .add((v) -> manager.newCompleteWorkflow(converter.toPayloads(v)));
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
            .setMarkerName(VERSION_MARKER_NAME)
            .putDetails(MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long timerStartedEventId1 = h.addGetEventId(EventType.EVENT_TYPE_TIMER_STARTED);
    h.add(
            EventType.EVENT_TYPE_TIMER_FIRED,
            TimerFiredEventAttributes.newBuilder()
                .setStartedEventId(timerStartedEventId1)
                .setTimerId("timer1"))
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_MARKER_RECORDED,
            markerBuilder
                .putDetails(MARKER_VERSION_KEY, converter.toPayloads(maxSupported).get())
                .build())
        .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    {
      // Full replay
      TestListener listener = new TestListener();
      manager = new WorkflowStateMachines(listener);
      try {
        h.handleWorkflowTaskTakeCommands(manager);
        fail("failure expected");
      } catch (NonDeterministicWorkflowError e) {
        assertTrue(e.getCause().getMessage().startsWith("Version is already set"));
      }
    }
  }
}
