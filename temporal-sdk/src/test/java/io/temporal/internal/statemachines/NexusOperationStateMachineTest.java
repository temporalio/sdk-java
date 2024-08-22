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

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ScheduleNexusOperationCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.NexusOperationFailureInfo;
import io.temporal.api.history.v1.*;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.workflow.Functions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Test;

public class NexusOperationStateMachineTest {
  private static final String OPERATION = "test-operation";
  private static final String SERVICE = "test-service";
  private static final String ENDPOINT = "test-endpoint";
  static final String OPERATION_ID = "test-operation-id";
  private final DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;
  private static final List<
          StateMachine<
              NexusOperationStateMachine.State,
              NexusOperationStateMachine.ExplicitEvent,
              NexusOperationStateMachine>>
      stateMachineList = new ArrayList<>();
  private WorkflowStateMachines stateMachines;

  private WorkflowStateMachines newStateMachines(TestEntityManagerListenerBase listener) {
    return new WorkflowStateMachines(listener, (stateMachineList::add));
  }

  @AfterClass
  public static void generateCoverage() {
    List<
            Transition<
                NexusOperationStateMachine.State,
                TransitionEvent<NexusOperationStateMachine.ExplicitEvent>>>
        missed =
            NexusOperationStateMachine.STATE_MACHINE_DEFINITION.getUnvisitedTransitions(
                stateMachineList);
    if (!missed.isEmpty()) {
      CommandsGeneratePlantUMLStateDiagrams.writeToFile(
          "test",
          NexusOperationStateMachine.class,
          NexusOperationStateMachine.STATE_MACHINE_DEFINITION.asPlantUMLStateDiagramCoverage(
              stateMachineList));
      fail(
          "NexusOperationStateMachine is missing test coverage for the following transitions:\n"
              + missed);
    }
  }

  @Test
  public void testSyncNexusOperationCompletion() {
    class TestNexusListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleNexusOperationCommandAttributes.Builder attributes =
            newScheduleNexusOperationCommandAttributesBuilder();
        builder
            .<Optional<Payload>, Failure>add2(
                (v, c) -> stateMachines.startNexusOperation(attributes.build(), (o, f) -> {}, c))
            .add(
                (pair) ->
                    stateMachines.completeWorkflow(
                        Optional.of(
                            Payloads.newBuilder().addPayloads(pair.getT1().get()).build())));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_NEXUS_OPERATION_SCHEDULED
        6: EVENT_TYPE_NEXUS_OPERATION_COMPLETED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long scheduledEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
              newNexusOperationScheduledEventAttributesBuilder().build());
      h.add(
          EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED,
          NexusOperationCompletedEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setRequestId("requestId")
              .setResult(converter.toPayload("result1").get()));
      h.addWorkflowTaskScheduledAndStarted();
      assertEquals(2, h.getWorkflowTaskCount());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION, commands);
      assertEquals(
          OPERATION, commands.get(0).getScheduleNexusOperationCommandAttributes().getOperation());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestNexusListener();
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
  public void testSyncNexusOperationFailure() {
    class TestNexusListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleNexusOperationCommandAttributes.Builder attributes =
            newScheduleNexusOperationCommandAttributesBuilder();
        builder
            .<Optional<Payload>, Failure>add2(
                (v, c) -> stateMachines.startNexusOperation(attributes.build(), (o, f) -> {}, c))
            .add((pair) -> stateMachines.failWorkflow(pair.getT2()));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_NEXUS_OPERATION_SCHEDULED
        6: EVENT_TYPE_NEXUS_OPERATION_FAILED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long scheduledEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
              newNexusOperationScheduledEventAttributesBuilder().build());
      h.add(
          EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED,
          NexusOperationFailedEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setRequestId("requestId")
              .setFailure(
                  Failure.newBuilder()
                      .setMessage("failed")
                      .setNexusOperationExecutionFailureInfo(
                          NexusOperationFailureInfo.newBuilder().build())
                      .build()));
      h.addWorkflowTaskScheduledAndStarted();
      assertEquals(2, h.getWorkflowTaskCount());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION, commands);
      assertEquals(
          OPERATION, commands.get(0).getScheduleNexusOperationCommandAttributes().getOperation());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION, commands);
      assertEquals(
          "failed",
          commands.get(0).getFailWorkflowExecutionCommandAttributes().getFailure().getMessage());
    }
  }

  @Test
  public void testSyncNexusOperationCanceled() {
    class TestNexusListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleNexusOperationCommandAttributes.Builder attributes =
            newScheduleNexusOperationCommandAttributesBuilder();
        builder
            .<Optional<Payload>, Failure>add2(
                (v, c) -> stateMachines.startNexusOperation(attributes.build(), (o, f) -> {}, c))
            .add((pair) -> stateMachines.failWorkflow(pair.getT2()));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_NEXUS_OPERATION_SCHEDULED
        6: EVENT_TYPE_NEXUS_OPERATION_CANCELED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long scheduledEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
              newNexusOperationScheduledEventAttributesBuilder().build());
      h.add(
          EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED,
          NexusOperationCanceledEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setRequestId("requestId")
              .setFailure(
                  Failure.newBuilder()
                      .setMessage("canceled")
                      .setNexusOperationExecutionFailureInfo(
                          NexusOperationFailureInfo.newBuilder().build())
                      .build()));
      h.addWorkflowTaskScheduledAndStarted();
      assertEquals(2, h.getWorkflowTaskCount());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION, commands);
      assertEquals(
          OPERATION, commands.get(0).getScheduleNexusOperationCommandAttributes().getOperation());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION, commands);
      assertEquals(
          "canceled",
          commands.get(0).getFailWorkflowExecutionCommandAttributes().getFailure().getMessage());
    }
  }

  @Test
  public void testSyncNexusOperationTimedout() {
    class TestNexusListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleNexusOperationCommandAttributes.Builder attributes =
            newScheduleNexusOperationCommandAttributesBuilder();
        builder
            .<Optional<Payload>, Failure>add2(
                (v, c) -> stateMachines.startNexusOperation(attributes.build(), (o, f) -> {}, c))
            .add((pair) -> stateMachines.failWorkflow(pair.getT2()));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_NEXUS_OPERATION_SCHEDULED
        6: EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long scheduledEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
              newNexusOperationScheduledEventAttributesBuilder().build());
      h.add(
          EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT,
          NexusOperationTimedOutEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setRequestId("requestId")
              .setFailure(
                  Failure.newBuilder()
                      .setMessage("timed out")
                      .setNexusOperationExecutionFailureInfo(
                          NexusOperationFailureInfo.newBuilder().build())
                      .build()));
      h.addWorkflowTaskScheduledAndStarted();
      assertEquals(2, h.getWorkflowTaskCount());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION, commands);
      assertEquals(
          OPERATION, commands.get(0).getScheduleNexusOperationCommandAttributes().getOperation());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION, commands);
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertCommand(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION, commands);
      assertEquals(
          "timed out",
          commands.get(0).getFailWorkflowExecutionCommandAttributes().getFailure().getMessage());
    }
  }

  @Test
  public void testSyncNexusOperationImmediateCancellation() {
    class TestNexusListener extends TestEntityManagerListenerBase {
      private Functions.Proc cancellationHandler;

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleNexusOperationCommandAttributes.Builder attributes =
            newScheduleNexusOperationCommandAttributesBuilder();
        builder
            .<Optional<Payload>, Failure>add2(
                (v, c) ->
                    cancellationHandler =
                        stateMachines.startNexusOperation(attributes.build(), (o, f) -> {}, c))
            .add((pair) -> stateMachines.failWorkflow(pair.getT2()));
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
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTaskScheduledAndStarted();
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION, commands);
    }
  }

  @Test
  public void testAsyncNexusOperationCompletion() {
    class TestNexusListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleNexusOperationCommandAttributes.Builder attributes =
            newScheduleNexusOperationCommandAttributesBuilder();
        DelayedCallback2<Optional<Payload>, Failure> delayedCallback = new DelayedCallback2();
        builder
            .<Optional<String>, Failure>add2(
                (v, c) ->
                    stateMachines.startNexusOperation(attributes.build(), c, delayedCallback::run))
            .<Optional<Payload>, Failure>add2(
                (pair, c) -> {
                  Assert.assertEquals(OPERATION_ID, pair.getT1().get());
                  delayedCallback.set(c);
                })
            .add(
                (pair) ->
                    stateMachines.completeWorkflow(
                        Optional.of(
                            Payloads.newBuilder().addPayloads(pair.getT1().get()).build())));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_NEXUS_OPERATION_SCHEDULED
        6: EVENT_TYPE_NEXUS_OPERATION_STARTED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
        9: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        6: EVENT_TYPE_NEXUS_OPERATION_COMPLETED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long scheduledEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
              newNexusOperationScheduledEventAttributesBuilder().build());
      h.add(
          EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED,
          NexusOperationStartedEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setRequestId("requestId")
              .setOperationId(OPERATION_ID)
              .build());
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED,
          NexusOperationCompletedEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setRequestId("requestId")
              .setResult(converter.toPayload("result1").get()));
      h.addWorkflowTaskScheduledAndStarted();
      assertEquals(3, h.getWorkflowTaskCount());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION, commands);
      assertEquals(
          OPERATION, commands.get(0).getScheduleNexusOperationCommandAttributes().getOperation());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertEquals(0, commands.size());
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
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
  public void testAsyncNexusOperationFailed() {
    class TestNexusListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleNexusOperationCommandAttributes.Builder attributes =
            newScheduleNexusOperationCommandAttributesBuilder();
        DelayedCallback2<Optional<Payload>, Failure> delayedCallback = new DelayedCallback2();
        builder
            .<Optional<String>, Failure>add2(
                (v, c) ->
                    stateMachines.startNexusOperation(attributes.build(), c, delayedCallback::run))
            .<Optional<Payload>, Failure>add2(
                (pair, c) -> {
                  Assert.assertEquals(OPERATION_ID, pair.getT1().get());
                  delayedCallback.set(c);
                })
            .add((pair) -> stateMachines.failWorkflow(pair.getT2()));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_NEXUS_OPERATION_SCHEDULED
        6: EVENT_TYPE_NEXUS_OPERATION_STARTED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
        9: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        6: EVENT_TYPE_NEXUS_OPERATION_FAILED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long scheduledEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
              newNexusOperationScheduledEventAttributesBuilder().build());
      h.add(
          EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED,
          NexusOperationStartedEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setRequestId("requestId")
              .setOperationId(OPERATION_ID)
              .build());
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED,
          NexusOperationFailedEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setRequestId("requestId")
              .setFailure(
                  Failure.newBuilder()
                      .setMessage("failed")
                      .setNexusOperationExecutionFailureInfo(
                          NexusOperationFailureInfo.newBuilder().build())
                      .build()));
      h.addWorkflowTaskScheduledAndStarted();
      assertEquals(3, h.getWorkflowTaskCount());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION, commands);
      assertEquals(
          OPERATION, commands.get(0).getScheduleNexusOperationCommandAttributes().getOperation());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertEquals(0, commands.size());
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertCommand(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION, commands);
      assertEquals(
          "failed",
          commands.get(0).getFailWorkflowExecutionCommandAttributes().getFailure().getMessage());
    }
  }

  @Test
  public void testAsyncNexusOperationCanceled() {
    class TestNexusListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleNexusOperationCommandAttributes.Builder attributes =
            newScheduleNexusOperationCommandAttributesBuilder();
        DelayedCallback2<Optional<Payload>, Failure> delayedCallback = new DelayedCallback2();
        builder
            .<Optional<String>, Failure>add2(
                (v, c) ->
                    stateMachines.startNexusOperation(attributes.build(), c, delayedCallback::run))
            .<Optional<Payload>, Failure>add2(
                (pair, c) -> {
                  Assert.assertEquals(OPERATION_ID, pair.getT1().get());
                  delayedCallback.set(c);
                })
            .add((pair) -> stateMachines.failWorkflow(pair.getT2()));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_NEXUS_OPERATION_SCHEDULED
        6: EVENT_TYPE_NEXUS_OPERATION_STARTED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
        9: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        6: EVENT_TYPE_NEXUS_OPERATION_CANCELED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long scheduledEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
              newNexusOperationScheduledEventAttributesBuilder().build());
      h.add(
          EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED,
          NexusOperationStartedEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setRequestId("requestId")
              .setOperationId(OPERATION_ID)
              .build());
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED,
          NexusOperationCanceledEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setRequestId("requestId")
              .setFailure(
                  Failure.newBuilder()
                      .setMessage("canceled")
                      .setNexusOperationExecutionFailureInfo(
                          NexusOperationFailureInfo.newBuilder().build())
                      .build()));
      h.addWorkflowTaskScheduledAndStarted();
      assertEquals(3, h.getWorkflowTaskCount());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION, commands);
      assertEquals(
          OPERATION, commands.get(0).getScheduleNexusOperationCommandAttributes().getOperation());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertEquals(0, commands.size());
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertCommand(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION, commands);
      assertEquals(
          "canceled",
          commands.get(0).getFailWorkflowExecutionCommandAttributes().getFailure().getMessage());
    }
  }

  @Test
  public void testAsyncNexusOperationTimeout() {
    class TestNexusListener extends TestEntityManagerListenerBase {

      @Override
      public void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ScheduleNexusOperationCommandAttributes.Builder attributes =
            newScheduleNexusOperationCommandAttributesBuilder();
        DelayedCallback2<Optional<Payload>, Failure> delayedCallback = new DelayedCallback2();
        builder
            .<Optional<String>, Failure>add2(
                (v, c) ->
                    stateMachines.startNexusOperation(attributes.build(), c, delayedCallback::run))
            .<Optional<Payload>, Failure>add2(
                (pair, c) -> {
                  Assert.assertEquals(OPERATION_ID, pair.getT1().get());
                  delayedCallback.set(c);
                })
            .add((pair) -> stateMachines.failWorkflow(pair.getT2()));
      }
    }

    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_NEXUS_OPERATION_SCHEDULED
        6: EVENT_TYPE_NEXUS_OPERATION_STARTED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
        9: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        6: EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h = new TestHistoryBuilder();
    {
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      h.add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
      h.addWorkflowTask();
      long scheduledEventId =
          h.addGetEventId(
              EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
              newNexusOperationScheduledEventAttributesBuilder().build());
      h.add(
          EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED,
          NexusOperationStartedEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setRequestId("requestId")
              .setOperationId(OPERATION_ID)
              .build());
      h.addWorkflowTask();
      h.add(
          EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT,
          NexusOperationTimedOutEventAttributes.newBuilder()
              .setScheduledEventId(scheduledEventId)
              .setRequestId("requestId")
              .setFailure(
                  Failure.newBuilder()
                      .setMessage("timed out")
                      .setNexusOperationExecutionFailureInfo(
                          NexusOperationFailureInfo.newBuilder().build())
                      .build()));
      h.addWorkflowTaskScheduledAndStarted();
      assertEquals(3, h.getWorkflowTaskCount());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION, commands);
      assertEquals(
          OPERATION, commands.get(0).getScheduleNexusOperationCommandAttributes().getOperation());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertEquals(0, commands.size());
    }
    {
      // Full replay
      TestEntityManagerListenerBase listener = new TestNexusListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 3);
      assertCommand(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION, commands);
      assertEquals(
          "timed out",
          commands.get(0).getFailWorkflowExecutionCommandAttributes().getFailure().getMessage());
    }
  }

  static ScheduleNexusOperationCommandAttributes.Builder
      newScheduleNexusOperationCommandAttributesBuilder() {
    return ScheduleNexusOperationCommandAttributes.newBuilder()
        .setOperation(OPERATION)
        .setService(SERVICE)
        .setEndpoint(ENDPOINT);
  }

  static NexusOperationScheduledEventAttributes.Builder
      newNexusOperationScheduledEventAttributesBuilder() {
    return NexusOperationScheduledEventAttributes.newBuilder()
        .setOperation(OPERATION)
        .setService(SERVICE)
        .setEndpoint(ENDPOINT);
  }

  public static class DelayedCallback2<T1, T2> {
    private final AtomicReference<Functions.Proc2<T1, T2>> callback = new AtomicReference<>();

    public void set(Functions.Proc2<T1, T2> callback) {
      this.callback.set(callback);
    }

    public void run(T1 t1, T2 t2) {
      callback.get().apply(t1, t2);
    }
  }
}
