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
import static org.junit.Assert.*;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.TimeoutFailureInfo;
import io.temporal.api.history.v1.ActivityTaskCancelRequestedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskCanceledEventAttributes;
import io.temporal.api.history.v1.ActivityTaskCompletedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskFailedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskScheduledEventAttributes;
import io.temporal.api.history.v1.ActivityTaskStartedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskTimedOutEventAttributes;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.replay.ExecuteActivityParameters;
import io.temporal.workflow.Functions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.AfterClass;
import org.junit.Test;

public class ActivityStateMachineTest {

  private final DataConverter converter = DataConverter.getDefaultInstance();
  private static final List<
          StateMachine<
              ActivityStateMachine.State, ActivityStateMachine.ExplicitEvent, ActivityStateMachine>>
      stateMachineList = new ArrayList<>();
  private WorkflowStateMachines stateMachines;

  private WorkflowStateMachines newStateMachines(TestEntityManagerListenerBase listener) {
    return new WorkflowStateMachines(
        listener, (stateMachine -> stateMachineList.add(stateMachine)));
  }

  @AfterClass
  public static void generateCoverage() {
    List<Transition> missed =
        ActivityStateMachine.STATE_MACHINE_DEFINITION.getUnvisitedTransitions(stateMachineList);
    if (!missed.isEmpty()) {
      CommandsGeneratePlantUMLStateDiagrams.writeToFile(
          "test",
          ActivityStateMachine.class,
          ActivityStateMachine.STATE_MACHINE_DEFINITION.asPlantUMLStateDiagramCoverage(
              stateMachineList));
      fail(
          "ActivityStateMachine is missing test coverage for the following transitions:\n"
              + missed);
    }
  }

  @Test
  public void testActivityCompletion() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> stateMachines.scheduleActivityTask(parameters, c))
            .add((pair) -> stateMachines.completeWorkflow(pair.getT1()));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_ACTIVITY_TASK_STARTED
        7: EVENT_TYPE_ACTIVITY_TASK_COMPLETED
        8: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        9: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long scheduledEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
              ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
      long startedEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
              ActivityTaskStartedEventAttributes.newBuilder()
                  .setScheduledEventId(scheduledEventId));
      h.add(
          EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED,
          ActivityTaskCompletedEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setStartedEventId(startedEventId)
              .setResult(converter.toPayloads("result1").get()));
      h.addWorkflowTaskScheduledAndStarted();
      assertEquals(2, h.getWorkflowTaskCount());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
      assertEquals(
          "id1", commands.get(0).getScheduleActivityTaskCommandAttributes().getActivityId());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
      assertEquals(
          "result1",
          converter.fromPayloads(
              0,
              Optional.of(
                  commands.get(0).getCompleteWorkflowExecutionCommandAttributes().getResult()),
              String.class,
              String.class));
    }
  }

  @Test
  public void testActivityFailure() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> stateMachines.scheduleActivityTask(parameters, c))
            .add(
                (pair) -> {
                  assertNotNull(pair.getT2());
                  assertEquals(
                      "type1", pair.getT2().getCause().getApplicationFailureInfo().getType());
                  stateMachines.completeWorkflow(Optional.empty());
                });
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_ACTIVITY_TASK_STARTED
        7: EVENT_TYPE_ACTIVITY_TASK_FAILED
        8: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        9: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long scheduledEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
              ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
      long startedEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
              ActivityTaskStartedEventAttributes.newBuilder()
                  .setScheduledEventId(scheduledEventId));
      h.add(
          EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED,
          ActivityTaskFailedEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setStartedEventId(startedEventId)
              .setFailure(
                  Failure.newBuilder()
                      .setApplicationFailureInfo(
                          ApplicationFailureInfo.newBuilder().setType("type1").build())));
      h.addWorkflowTaskScheduledAndStarted();
      assertEquals(2, h.getWorkflowTaskCount());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
      assertEquals(
          "id1", commands.get(0).getScheduleActivityTaskCommandAttributes().getActivityId());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testActivityTimeout() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> stateMachines.scheduleActivityTask(parameters, c))
            .add(
                (pair) -> {
                  assertNotNull(pair.getT2());
                  assertEquals(
                      TimeoutType.TIMEOUT_TYPE_HEARTBEAT,
                      pair.getT2().getCause().getTimeoutFailureInfo().getTimeoutType());
                  stateMachines.completeWorkflow(Optional.empty());
                });
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_ACTIVITY_TASK_STARTED
        7: EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT
        8: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        9: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long scheduledEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
              ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
      long startedEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
              ActivityTaskStartedEventAttributes.newBuilder()
                  .setScheduledEventId(scheduledEventId));
      h.add(
          EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
          ActivityTaskTimedOutEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setStartedEventId(startedEventId)
              .setFailure(
                  Failure.newBuilder()
                      .setTimeoutFailureInfo(
                          TimeoutFailureInfo.newBuilder()
                              .setTimeoutType(TimeoutType.TIMEOUT_TYPE_HEARTBEAT)
                              .build())));
      h.addWorkflowTaskScheduledAndStarted();
      assertEquals(2, h.getWorkflowTaskCount());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
      assertEquals(
          "id1", commands.get(0).getScheduleActivityTaskCommandAttributes().getActivityId());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testImmediateActivityCancellation() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> cancellationHandler = stateMachines.scheduleActivityTask(parameters, c))
            .add(
                (pair) -> {
                  assertNotNull(pair.getT2());
                  assertEquals(
                      Failure.FailureInfoCase.CANCELED_FAILURE_INFO,
                      pair.getT2().getCause().getFailureInfoCase());
                  stateMachines.completeWorkflow(Optional.empty());
                });

        // Immediate cancellation
        builder.add((v) -> cancellationHandler.apply());
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTaskScheduledAndStarted();
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testScheduledActivityCancellation() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> cancellationHandler = stateMachines.scheduleActivityTask(parameters, c))
            .add(
                (pair) -> {
                  assertNotNull(pair.getT2());
                  assertEquals(
                      Failure.FailureInfoCase.CANCELED_FAILURE_INFO,
                      pair.getT2().getCause().getFailureInfoCase());
                  stateMachines.completeWorkflow(Optional.empty());
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
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED: 1
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED: 2
        9: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        10: EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED
        11: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        12: EVENT_TYPE_WORKFLOW_TASK_STARTED: 3
        13: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        14: EVENT_TYPE_ACTIVITY_TASK_CANCELED
        15: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        16: EVENT_TYPE_WORKFLOW_TASK_STARTED: 4
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long scheduledEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
    h.add(
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
            WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"))
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
            ActivityTaskCancelRequestedEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .build())
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED,
            ActivityTaskCanceledEventAttributes.newBuilder().setScheduledEventId(scheduledEventId))
        .addWorkflowTaskScheduledAndStarted();

    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertTrue(commands.isEmpty());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 4);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testScheduledActivityCancellationWhileTimeout() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> cancellationHandler = stateMachines.scheduleActivityTask(parameters, c))
            .add(
                (pair) -> {
                  assertNotNull(pair.getT2());
                  assertEquals(
                      Failure.FailureInfoCase.TIMEOUT_FAILURE_INFO,
                      pair.getT2().getCause().getFailureInfoCase());
                  stateMachines.completeWorkflow(Optional.empty());
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
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        7: EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT
        8: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        9: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long scheduledEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
    h.add(
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
            WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"))
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
            ActivityTaskTimedOutEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .setFailure(
                    Failure.newBuilder()
                        .setTimeoutFailureInfo(TimeoutFailureInfo.getDefaultInstance())))
        .addWorkflowTaskScheduledAndStarted();

    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testScheduledActivityCancellationLaterTimeout() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> cancellationHandler = stateMachines.scheduleActivityTask(parameters, c))
            .add(
                (pair) -> {
                  assertNotNull(pair.getT2());
                  assertEquals(
                      Failure.FailureInfoCase.TIMEOUT_FAILURE_INFO,
                      pair.getT2().getCause().getFailureInfoCase());
                  stateMachines.completeWorkflow(Optional.empty());
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
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
        9: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        10: EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED
        11: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        12: EVENT_TYPE_WORKFLOW_TASK_STARTED
        13: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        14: EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT
        15: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        16: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long scheduledEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
    h.add(
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
            WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"))
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
            ActivityTaskCancelRequestedEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .build())
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
            ActivityTaskTimedOutEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .setFailure(
                    Failure.newBuilder()
                        .setTimeoutFailureInfo(TimeoutFailureInfo.getDefaultInstance())))
        .addWorkflowTaskScheduledAndStarted();

    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertTrue(commands.isEmpty());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 4);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testStartedActivityCancellation() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> cancellationHandler = stateMachines.scheduleActivityTask(parameters, c))
            .add(
                (pair) -> {
                  assertNotNull(pair.getT2());
                  assertEquals(
                      Failure.FailureInfoCase.CANCELED_FAILURE_INFO,
                      pair.getT2().getCause().getFailureInfoCase());
                  stateMachines.completeWorkflow(Optional.empty());
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
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED: 1
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        7: EVENT_TYPE_WORKFLOW_TASK_STARTED: 2
        8: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        9: EVENT_TYPE_ACTIVITY_TASK_STARTED
        10: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        11: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        12: EVENT_TYPE_WORKFLOW_TASK_STARTED: 3
        13: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        14: EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED
        15: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        16: EVENT_TYPE_WORKFLOW_TASK_STARTED: 4
        17: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        18: EVENT_TYPE_ACTIVITY_TASK_CANCELED
        19: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        20: EVENT_TYPE_WORKFLOW_TASK_STARTED: 5
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long scheduledEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
    h.addWorkflowTask();
    long startedEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            ActivityTaskStartedEventAttributes.newBuilder().setScheduledEventId(scheduledEventId));
    h.add(
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
            WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"))
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
            ActivityTaskCancelRequestedEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .build())
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED,
            ActivityTaskCanceledEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .setStartedEventId(startedEventId)
                .build())
        .addWorkflowTaskScheduledAndStarted();

    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertTrue(commands.isEmpty());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertCommand(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 4);
      assertTrue(commands.isEmpty());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 5);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testStartedActivityCancellationTimeout() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> cancellationHandler = stateMachines.scheduleActivityTask(parameters, c))
            .add(
                (pair) -> {
                  assertNotNull(pair.getT2());
                  assertEquals(
                      Failure.FailureInfoCase.TIMEOUT_FAILURE_INFO,
                      pair.getT2().getCause().getFailureInfoCase());
                  stateMachines.completeWorkflow(Optional.empty());
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
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        7: EVENT_TYPE_WORKFLOW_TASK_STARTED
        8: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        9: EVENT_TYPE_ACTIVITY_TASK_STARTED
        10: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        11: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        12: EVENT_TYPE_WORKFLOW_TASK_STARTED
        13: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        14: EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED
        15: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        16: EVENT_TYPE_WORKFLOW_TASK_STARTED
        17: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        18: EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT
        19: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        20: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long scheduledEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
    h.addWorkflowTask();
    long startedEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            ActivityTaskStartedEventAttributes.newBuilder().setScheduledEventId(scheduledEventId));
    h.add(
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
            WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"))
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
            ActivityTaskCancelRequestedEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .build())
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
            ActivityTaskTimedOutEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .setStartedEventId(startedEventId)
                .setFailure(
                    Failure.newBuilder()
                        .setTimeoutFailureInfo(TimeoutFailureInfo.getDefaultInstance())))
        .addWorkflowTaskScheduledAndStarted();

    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertTrue(commands.isEmpty());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertCommand(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 4);
      assertTrue(commands.isEmpty());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 5);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testStartedActivityCancellationWhileTimeout() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> cancellationHandler = stateMachines.scheduleActivityTask(parameters, c))
            .add(
                (pair) -> {
                  assertNotNull(pair.getT2());
                  assertEquals(
                      Failure.FailureInfoCase.TIMEOUT_FAILURE_INFO,
                      pair.getT2().getCause().getFailureInfoCase());
                  stateMachines.completeWorkflow(Optional.empty());
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
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        7: EVENT_TYPE_WORKFLOW_TASK_STARTED
        8: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        9: EVENT_TYPE_ACTIVITY_TASK_STARTED
        10: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        11: EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT
        12: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        13: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long scheduledEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
    h.addWorkflowTask();
    long startedEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            ActivityTaskStartedEventAttributes.newBuilder().setScheduledEventId(scheduledEventId));
    h.add(
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
            WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"))
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
            ActivityTaskTimedOutEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .setStartedEventId(startedEventId)
                .setFailure(
                    Failure.newBuilder()
                        .setTimeoutFailureInfo(TimeoutFailureInfo.getDefaultInstance())))
        .addWorkflowTaskScheduledAndStarted();
    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertTrue(commands.isEmpty());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testStartedActivityCancellationFailed() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> cancellationHandler = stateMachines.scheduleActivityTask(parameters, c))
            .add(
                (pair) -> {
                  assertNotNull(pair.getT2());
                  assertEquals(
                      "type1", pair.getT2().getCause().getApplicationFailureInfo().getType());
                  stateMachines.completeWorkflow(Optional.empty());
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
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        7: EVENT_TYPE_WORKFLOW_TASK_STARTED
        8: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        9: EVENT_TYPE_ACTIVITY_TASK_STARTED
        10: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        11: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        12: EVENT_TYPE_WORKFLOW_TASK_STARTED
        13: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        14: EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED
        15: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        16: EVENT_TYPE_WORKFLOW_TASK_STARTED
        17: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        18: EVENT_TYPE_ACTIVITY_TASK_FAILED
        19: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        20: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long scheduledEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
    h.addWorkflowTask();
    long startedEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            ActivityTaskStartedEventAttributes.newBuilder().setScheduledEventId(scheduledEventId));
    h.add(
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
            WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"))
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
            ActivityTaskCancelRequestedEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .build())
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED,
            ActivityTaskFailedEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .setStartedEventId(startedEventId)
                .setFailure(
                    Failure.newBuilder()
                        .setApplicationFailureInfo(
                            ApplicationFailureInfo.newBuilder().setType("type1").build())))
        .addWorkflowTaskScheduledAndStarted();

    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertTrue(commands.isEmpty());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertCommand(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 4);
      assertTrue(commands.isEmpty());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 5);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testScheduledActivityCancellationWhileStarted() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> cancellationHandler = stateMachines.scheduleActivityTask(parameters, c))
            .add(
                (pair) -> {
                  assertNotNull(pair.getT2());
                  assertEquals(
                      Failure.FailureInfoCase.CANCELED_FAILURE_INFO,
                      pair.getT2().getCause().getFailureInfoCase());
                  stateMachines.completeWorkflow(Optional.empty());
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
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        7: EVENT_TYPE_ACTIVITY_TASK_STARTED
        8: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        9: EVENT_TYPE_WORKFLOW_TASK_STARTED
        10: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        11: EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED
        12: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        13: EVENT_TYPE_WORKFLOW_TASK_STARTED
        14: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        15: EVENT_TYPE_ACTIVITY_TASK_CANCELED
        16: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        17: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long scheduledEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
    h.add(
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
        WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"));
    long startedEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            ActivityTaskStartedEventAttributes.newBuilder().setScheduledEventId(scheduledEventId));
    h.addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
            ActivityTaskCancelRequestedEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .build())
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED,
            ActivityTaskCanceledEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .setStartedEventId(startedEventId))
        .addWorkflowTaskScheduledAndStarted();

    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertTrue(commands.isEmpty());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 4);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testScheduledActivityCancellationBufferedStarted() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> cancellationHandler = stateMachines.scheduleActivityTask(parameters, c))
            .add(
                (pair) -> {
                  assertNotNull(pair.getT2());
                  assertEquals(
                      Failure.FailureInfoCase.CANCELED_FAILURE_INFO,
                      pair.getT2().getCause().getFailureInfoCase());
                  stateMachines.completeWorkflow(Optional.empty());
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
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED: 1
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED: 2
        9: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        10: EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED
        11: EVENT_TYPE_ACTIVITY_TASK_STARTED
        12: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        13: EVENT_TYPE_WORKFLOW_TASK_STARTED: 3
        14: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        15: EVENT_TYPE_ACTIVITY_TASK_CANCELED
        16: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        17: EVENT_TYPE_WORKFLOW_TASK_STARTED: 4
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long scheduledEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
    h.add(
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
            WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"))
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
            ActivityTaskCancelRequestedEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .build());
    long startedEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            ActivityTaskStartedEventAttributes.newBuilder().setScheduledEventId(scheduledEventId));
    h.addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED,
            ActivityTaskCanceledEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .setStartedEventId(startedEventId))
        .addWorkflowTaskScheduledAndStarted();
    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertTrue(commands.isEmpty());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 4);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testScheduledActivityCancellationBufferedStartedCompleted() {
    class TestActivityListener extends TestEntityManagerListenerBase {

      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleActivityTaskCommandAttributes.Builder attributes =
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId("id1");
        ExecuteActivityParameters parameters =
            new ExecuteActivityParameters(
                attributes, ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        builder
            .<Optional<Payloads>, Failure>add2(
                (v, c) -> cancellationHandler = stateMachines.scheduleActivityTask(parameters, c))
            .add((pair) -> stateMachines.completeWorkflow(pair.getT1()));
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
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED: 1
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
        6: EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED: 2
        9: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        10: EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED
        11: EVENT_TYPE_ACTIVITY_TASK_STARTED
        12: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        13: EVENT_TYPE_WORKFLOW_TASK_STARTED: 3
        14: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        15: EVENT_TYPE_ACTIVITY_TASK_CANCELED
        16: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        17: EVENT_TYPE_WORKFLOW_TASK_STARTED: 4
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask();
    long scheduledEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            ActivityTaskScheduledEventAttributes.newBuilder().setActivityId("id1").build());
    h.add(
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
            WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName("signal1"))
        .addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
            ActivityTaskCancelRequestedEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .build());
    long startedEventId =
        h.addGetEventId(
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            ActivityTaskStartedEventAttributes.newBuilder().setScheduledEventId(scheduledEventId));
    h.addWorkflowTask()
        .add(
            EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED,
            ActivityTaskCompletedEventAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId)
                .setStartedEventId(startedEventId)
                .setResult(converter.toPayloads("result1").get()))
        .addWorkflowTaskScheduledAndStarted();
    {
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK, commands);
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertTrue(commands.isEmpty());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 4);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
      assertEquals(
          "result1",
          converter.fromPayloads(
              0,
              Optional.of(
                  commands.get(0).getCompleteWorkflowExecutionCommandAttributes().getResult()),
              String.class,
              String.class));
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestActivityListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
      assertEquals(
          "result1",
          converter.fromPayloads(
              0,
              Optional.of(
                  commands.get(0).getCompleteWorkflowExecutionCommandAttributes().getResult()),
              String.class,
              String.class));
    }
  }
}
