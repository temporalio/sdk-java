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
import io.temporal.internal.history.MarkerUtils;
import io.temporal.internal.history.VersionMarkerUtils;
import io.temporal.internal.replay.InternalWorkflowTaskException;
import io.temporal.worker.NonDeterministicException;
import io.temporal.workflow.Functions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.AfterClass;
import org.junit.Test;

public class VersionStateMachineTest {

  private final DataConverter converter = DataConverter.getDefaultInstance();
  private WorkflowStateMachines stateMachines;

  private static final List<
          StateMachine<
              VersionStateMachine.State,
              VersionStateMachine.ExplicitEvent,
              VersionStateMachine.InvocationStateMachine>>
      stateMachineList = new ArrayList<>();

  private WorkflowStateMachines newStateMachines(TestEntityManagerListenerBase listener) {
    return new WorkflowStateMachines(
        listener, (stateMachine -> stateMachineList.add(stateMachine)));
  }

  @AfterClass
  public static void generateCoverage() {
    List<Transition> missed =
        VersionStateMachine.STATE_MACHINE_DEFINITION.getUnvisitedTransitions(stateMachineList);
    if (!missed.isEmpty()) {
      CommandsGeneratePlantUMLStateDiagrams.writeToFile(
          "test",
          VersionStateMachine.class,
          VersionStateMachine.STATE_MACHINE_DEFINITION.asPlantUMLStateDiagramCoverage(
              stateMachineList));
      fail(
          "VersionStateMachine is missing test coverage for the following transitions:\n" + missed);
    }
  }

  @Test
  public void testOne() {
    final int maxSupported = 12;
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .add((v) -> stateMachines.completeWorkflow(converter.toPayloads(v.getT1())));
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
            .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
            .putDetails(VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(
                        VersionMarkerUtils.MARKER_VERSION_KEY,
                        converter.toPayloads(maxSupported).get())
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
      assertEquals(
          maxSupported, (int) converter.fromPayloads(0, resultData, Integer.class, Integer.class));
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
  public void testMultiple() {
    final int maxSupported = 13;
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .<Integer, RuntimeException>add2(
                (v, c) -> {
                  assertNull(v.getT2());
                  stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported + 10, c);
                })
            .<Integer, RuntimeException>add2(
                (v, c) -> {
                  assertNull(v.getT2());
                  stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported + 100, c);
                })
            .add(
                (v) -> {
                  assertNull(v.getT2());
                  stateMachines.completeWorkflow(converter.toPayloads(v.getT1()));
                });
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
            .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
            .putDetails(VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(
                        VersionMarkerUtils.MARKER_VERSION_KEY,
                        converter.toPayloads(maxSupported).get())
                    .build())
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    {
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);

      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      int version =
          converter.fromPayloads(
              0,
              Optional.ofNullable(
                  commands
                      .get(0)
                      .getRecordMarkerCommandAttributes()
                      .getDetailsOrThrow(VersionMarkerUtils.MARKER_VERSION_KEY)),
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
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertTrue(commands.isEmpty());
    }
  }

  @Test
  public void testUnsupportedVersion() {
    final int maxSupported = 13;
    AtomicReference<RuntimeException> secondVersionCallException = new AtomicReference<>();
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .<Integer, RuntimeException>add2(
                (v, c) -> {
                  assertNull(v.getT2());
                  stateMachines.getVersion("id1", maxSupported + 10, maxSupported + 10, c);
                })
            .add(
                (v) -> {
                  secondVersionCallException.set(v.getT2());
                  stateMachines.completeWorkflow(converter.toPayloads(v));
                });
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
            .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
            .putDetails(VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(
                        VersionMarkerUtils.MARKER_VERSION_KEY,
                        converter.toPayloads(maxSupported).get())
                    .build())
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    {
      secondVersionCallException.set(null);
      // test execution
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      h.handleWorkflowTaskTakeCommands(stateMachines, 0, Integer.MAX_VALUE);

      assertNotNull(secondVersionCallException.get());
      assertTrue(
          secondVersionCallException
              .get()
              .getMessage()
              .startsWith("Version " + maxSupported + " of changeId id1 is not supported"));
    }

    {
      secondVersionCallException.set(null);
      // test full replay
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      h.handleWorkflowTaskTakeCommands(stateMachines);

      assertNotNull(secondVersionCallException.get());
      assertTrue(
          secondVersionCallException
              .get()
              .getMessage()
              .startsWith("Version " + maxSupported + " of changeId id1 is not supported"));
    }
  }

  /**
   * Tests when 1. an original execution is performed without any versions specified
   * (DEFAULT_VERSION is assumed) 2. replay is performed with code that has a version call with a
   * range no longer including DEFAULT_VERSION
   */
  @Test
  public void unsupportedVersionAddedOnReplay() {
    AtomicReference<RuntimeException> versionCallException = new AtomicReference<>();

    class ReplayTestListener extends TestEntityManagerListenerBase {
      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer, RuntimeException>add2((v, c) -> stateMachines.getVersion("id1", 1, 1, c))
            .add(
                (v) -> {
                  versionCallException.set(v.getT2());
                  stateMachines.completeWorkflow(Optional.empty());
                });
      }
    }
    /*
      1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
      2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
      3: EVENT_TYPE_WORKFLOW_TASK_STARTED
      4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
      6: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    {
      versionCallException.set(null);
      // test full replay
      TestEntityManagerListenerBase listener = new ReplayTestListener();
      stateMachines = newStateMachines(listener);
      h.handleWorkflowTaskTakeCommands(stateMachines);

      assertNotNull(versionCallException.get());
      assertTrue(
          versionCallException
              .get()
              .getMessage()
              .startsWith("Version " + DEFAULT_VERSION + " of changeId id1 is not supported"));
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
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .<Integer, RuntimeException>add2(
                (v, c) -> {
                  trace.append(v.getT1()).append(", ");
                  stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported + 10, c);
                })
            .<Integer, RuntimeException>add2(
                (v, c) -> {
                  trace.append(v.getT1()).append(", ");
                  stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported + 100, c);
                })
            .<HistoryEvent>add1(
                (v, c) -> {
                  trace.append(v.getT1());
                  stateMachines.newTimer(
                      StartTimerCommandAttributes.newBuilder()
                          .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                          .build(),
                      c);
                })
            .add((v) -> stateMachines.completeWorkflow(converter.toPayloads(v)));
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
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
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
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .add(
                (v) -> {
                  assertNull(v.getT2());
                  stateMachines.completeWorkflow(converter.toPayloads(v));
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
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
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
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .<Integer, RuntimeException>add2(
                (v, c) -> {
                  trace.append(v.getT1()).append(", ");
                  stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported + 10, c);
                })
            .<HistoryEvent>add1(
                (v, c) -> {
                  trace.append(v.getT1()).append(", ");
                  stateMachines.newTimer(
                      StartTimerCommandAttributes.newBuilder()
                          .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                          .build(),
                      c);
                })
            .<HistoryEvent>add1(
                (v, c) ->
                    stateMachines.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        c))
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", maxSupported - 3, maxSupported + 10, c))
            .<Integer, RuntimeException>add2(
                (v, c) -> {
                  trace.append(v.getT1()).append(", ");
                  stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported + 100, c);
                })
            .add(
                (v) -> {
                  trace.append(v.getT1());
                  stateMachines.completeWorkflow(converter.toPayloads(v.getT1()));
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
            .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
            .putDetails(VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(
                        VersionMarkerUtils.MARKER_VERSION_KEY,
                        converter.toPayloads(maxSupported).get())
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
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);

      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      int version =
          converter.fromPayloads(
              0,
              Optional.ofNullable(
                  commands
                      .get(0)
                      .getRecordMarkerCommandAttributes()
                      .getDetailsOrThrow(VersionMarkerUtils.MARKER_VERSION_KEY)),
              Integer.class,
              Integer.class);
      assertEquals(maxSupported, version);
      assertEquals(CommandType.COMMAND_TYPE_START_TIMER, commands.get(1).getCommandType());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_START_TIMER, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
      Optional<Payloads> resultData =
          Optional.of(commands.get(0).getCompleteWorkflowExecutionCommandAttributes().getResult());
      assertEquals(
          maxSupported, (int) converter.fromPayloads(0, resultData, Integer.class, Integer.class));
    }
    {
      // Full replay
      TestListener listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
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
            /*.<Integer>add((v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported, c))*/
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported + 10, c))
            .<HistoryEvent>add1(
                (v, c) -> {
                  trace.append(v.getT1()).append(", ");
                  stateMachines.newTimer(
                      StartTimerCommandAttributes.newBuilder()
                          .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                          .build(),
                      c);
                })
            .<HistoryEvent>add1(
                (v, c) ->
                    stateMachines.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        c))
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", maxSupported - 3, maxSupported + 10, c))
            /*.<Integer>add((v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported + 100, c));*/
            .add(
                (v) -> {
                  trace.append(v.getT1());
                  stateMachines.completeWorkflow(converter.toPayloads(v));
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
            .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
            .putDetails(VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(
                        VersionMarkerUtils.MARKER_VERSION_KEY,
                        converter.toPayloads(maxSupported).get())
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
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
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
                    stateMachines.getVersion(
                        "id2",
                        DEFAULT_VERSION,
                        maxSupported,
                        (r, e) -> {
                          assertNull(e);
                          versionId2 = r;
                          c.apply(r);
                        }))
            .<HistoryEvent>add1(
                (v, c) ->
                    stateMachines.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        c))
            .add((v) -> stateMachines.completeWorkflow(converter.toPayloads(v)));
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
                    .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
                    .putDetails(
                        VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get())
                    .putDetails(
                        VersionMarkerUtils.MARKER_VERSION_KEY,
                        converter.toPayloads(maxSupported + 10).get())
                    .build())
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                MarkerRecordedEventAttributes.newBuilder()
                    .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
                    .putDetails(
                        VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id2").get())
                    .putDetails(
                        VersionMarkerUtils.MARKER_VERSION_KEY,
                        converter.toPayloads(maxSupported).get())
                    .build())
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                MarkerRecordedEventAttributes.newBuilder()
                    .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
                    .putDetails(
                        VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id3").get())
                    .putDetails(
                        VersionMarkerUtils.MARKER_VERSION_KEY,
                        converter.toPayloads(maxSupported + 20).get())
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
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
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
            .<Integer>add((v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
                .<Integer>add(
                        (v, c) -> {
                          trace.append(v + ", ");
                          stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported + 10, c);
                        })
                 */
            .<HistoryEvent>add1(
                (v, c) ->
                    stateMachines.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        c))
            .<HistoryEvent>add1(
                (v, c) ->
                    stateMachines.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        c))
            /*.<Integer>add(
            (v, c) -> stateMachines.getVersion("id1", maxSupported - 3, maxSupported + 10, c))*/
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported + 100, c))
            .add(
                (v) -> {
                  trace.append(v.getT1());
                  stateMachines.completeWorkflow(converter.toPayloads(v.getT1()));
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
            .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
            .putDetails(VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(
                        VersionMarkerUtils.MARKER_VERSION_KEY,
                        converter.toPayloads(maxSupported).get())
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
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_START_TIMER, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
      Optional<Payloads> resultData =
          Optional.of(commands.get(0).getCompleteWorkflowExecutionCommandAttributes().getResult());
      assertEquals(
          maxSupported, (int) converter.fromPayloads(0, resultData, Integer.class, Integer.class));
    }
    {
      // Full replay
      TestListener listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertTrue(commands.isEmpty());
      assertEquals(String.valueOf(maxSupported), listener.trace.toString());
    }
  }

  /**
   * This test simulates a situation when EVENT_TYPE_MARKER_RECORDED is the last event of WFT and
   * the corresponded getVersion call is removed. This situation requires a special handling in
   * states machines code because of the way how we process version events.
   */
  @Test
  public void versionMarkerIsTheLastCommandEventOfWFTWithoutCommand() {
    final int maxSupported = 12654;
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<HistoryEvent>add1(
                (v, c) ->
                    stateMachines.newTimer(
                        StartTimerCommandAttributes.newBuilder()
                            .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                            .build(),
                        c))
            .add(
                (v) -> {
                  stateMachines.completeWorkflow(converter.toPayloads(v));
                });
      }
    }
    /*
      1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
      2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
      3: EVENT_TYPE_WORKFLOW_TASK_STARTED
      4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
      5: EVENT_TYPE_TIMER_STARTED
      6: EVENT_TYPE_MARKER_RECORDED
      7: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */
    MarkerRecordedEventAttributes.Builder markerBuilder =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
            .putDetails(VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
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
        .add(
            EventType.EVENT_TYPE_MARKER_RECORDED,
            markerBuilder
                .putDetails(
                    VersionMarkerUtils.MARKER_VERSION_KEY, converter.toPayloads(maxSupported).get())
                .build())
        .addWorkflowTask()
        .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    {
      // Full replay
      TestListener listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertTrue(commands.isEmpty());
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
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .<HistoryEvent>add1(
                (v, c) -> {
                  assertNull(v.getT2());
                  stateMachines.newTimer(
                      StartTimerCommandAttributes.newBuilder()
                          .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                          .build(),
                      c);
                })
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported + 100, c))
            .add(
                (v) -> {
                  assertNull(v.getT2());
                  stateMachines.completeWorkflow(converter.toPayloads(v));
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
            .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
            .putDetails(VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
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
                .putDetails(
                    VersionMarkerUtils.MARKER_VERSION_KEY, converter.toPayloads(maxSupported).get())
                .build())
        .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    {
      // Partial execution
      TestListener listener = new TestListener();
      stateMachines = newStateMachines(listener);
      try {
        h.handleWorkflowTaskTakeCommands(stateMachines, 1, Integer.MAX_VALUE);
        fail("failure expected");
      } catch (NonDeterministicException e) {
        assertTrue(e.getMessage().contains("Version is already set"));
      }
    }

    {
      // Full replay
      TestListener listener = new TestListener();
      stateMachines = newStateMachines(listener);
      try {
        h.handleWorkflowTaskTakeCommands(stateMachines);
        fail("failure expected");
      } catch (NonDeterministicException e) {
        assertTrue(e.getMessage().contains("Version is already set"));
      }
    }
  }

  /**
   * This test provides a localized reproduction for a state machine issue
   * https://github.com/temporalio/sdk-java/issues/615 This test has a corresponding full workflow
   * test {@link io.temporal.workflow.versionTests.GetVersionAfterScopeCancellationTest}
   */
  @Test
  public void testRecordAfterCommandCancellation() {
    final int maxSupported = 133;
    class TestListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        AtomicReference<Functions.Proc> cancelTimerProc = new AtomicReference<>();

        builder
            .add(
                (v) ->
                    cancelTimerProc.set(
                        stateMachines.newTimer(
                            StartTimerCommandAttributes.newBuilder()
                                .setStartToFireTimeout(
                                    Duration.newBuilder().setSeconds(100).build())
                                .build(),
                            ignore -> {})))
            .add((v) -> cancelTimerProc.get().apply())
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", maxSupported - 3, maxSupported + 10, c))
            .<HistoryEvent>add1(
                (v, c) -> {
                  assertNull(v.getT2());
                  // we add this to don't allow workflow complete command to be sent here
                  stateMachines.newTimer(
                      StartTimerCommandAttributes.newBuilder()
                          .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                          .build(),
                      c);
                })
            .add((v) -> stateMachines.completeWorkflow(converter.toPayloads(null)));
      }
    }
    /*
      1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
      2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
      3: EVENT_TYPE_WORKFLOW_TASK_STARTED
      4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
      5: EVENT_TYPE_MARKER_RECORDED
      17: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */
    MarkerRecordedEventAttributes.Builder markerBuilder =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
            .putDetails(VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(
                        VersionMarkerUtils.MARKER_VERSION_KEY,
                        converter.toPayloads(maxSupported).get())
                    .build());

    long timerStartedEventId1 = h.addGetEventId(EventType.EVENT_TYPE_TIMER_STARTED);
    h.add(
            EventType.EVENT_TYPE_TIMER_FIRED,
            TimerFiredEventAttributes.newBuilder()
                .setStartedEventId(timerStartedEventId1)
                .setTimerId("timer1"))
        .addWorkflowTask();
    h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    {
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);

      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      assertEquals(CommandType.COMMAND_TYPE_START_TIMER, commands.get(1).getCommandType());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertEquals(1, commands.size());
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  /** It is not allowed to prepend getVersion calls with existing changeId in the same WFT. */
  @Test
  public void testPrependingGetVersionInTheSameWFTWithExistingIdFails() {
    final int maxSupported = 133;
    class TestListener extends TestEntityManagerListenerBase {

      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        builder
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .<HistoryEvent>add1(
                (v, c) -> {
                  assertNull(v.getT2());
                  stateMachines.newTimer(
                      StartTimerCommandAttributes.newBuilder()
                          .setStartToFireTimeout(Duration.newBuilder().setSeconds(100).build())
                          .build(),
                      c);
                })
            .<Integer, RuntimeException>add2(
                (v, c) -> stateMachines.getVersion("id1", DEFAULT_VERSION, maxSupported, c))
            .add(
                (v) -> {
                  assertNull(v.getT2());
                  stateMachines.completeWorkflow(converter.toPayloads(v));
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
      7: EVENT_TYPE_MARKER_RECORDED
      8: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */
    MarkerRecordedEventAttributes.Builder markerBuilder =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
            .putDetails(VersionMarkerUtils.MARKER_CHANGE_ID_KEY, converter.toPayloads("id1").get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    h.addGetEventId(EventType.EVENT_TYPE_TIMER_STARTED);
    h.add(
            EventType.EVENT_TYPE_MARKER_RECORDED,
            markerBuilder
                .putDetails(
                    VersionMarkerUtils.MARKER_VERSION_KEY, converter.toPayloads(maxSupported).get())
                .build())
        .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);

    {
      // Full replay
      TestListener listener = new TestListener();
      stateMachines = newStateMachines(listener);
      try {
        h.handleWorkflowTaskTakeCommands(stateMachines);
        fail("failure expected");
      } catch (InternalWorkflowTaskException e) {
        assertTrue(
            e.getCause()
                .getCause()
                .getMessage()
                .startsWith("getVersion call before the existing version marker event."));
      }
    }
  }
}
