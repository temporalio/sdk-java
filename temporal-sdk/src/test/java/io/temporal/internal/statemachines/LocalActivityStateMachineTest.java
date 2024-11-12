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

import static io.temporal.internal.history.LocalActivityMarkerUtils.MARKER_ACTIVITY_RESULT_KEY;
import static io.temporal.internal.history.LocalActivityMarkerUtils.MARKER_TIME_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.*;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.history.LocalActivityMarkerUtils;
import io.temporal.internal.worker.LocalActivityResult;
import io.temporal.workflow.ChildWorkflowCancellationType;
import io.temporal.workflow.Functions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.AfterClass;
import org.junit.Test;

public class LocalActivityStateMachineTest {

  private final DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;
  private WorkflowStateMachines stateMachines;

  private static final List<
          StateMachine<
              LocalActivityStateMachine.State,
              LocalActivityStateMachine.ExplicitEvent,
              LocalActivityStateMachine>>
      stateMachineList = new ArrayList<>();

  private WorkflowStateMachines newStateMachines(TestEntityManagerListenerBase listener) {
    return new WorkflowStateMachines(listener, (stateMachineList::add));
  }

  @AfterClass
  public static void generateCoverage() {
    List<
            Transition<
                LocalActivityStateMachine.State,
                TransitionEvent<LocalActivityStateMachine.ExplicitEvent>>>
        missed =
            LocalActivityStateMachine.STATE_MACHINE_DEFINITION.getUnvisitedTransitions(
                stateMachineList);
    if (!missed.isEmpty()) {
      CommandsGeneratePlantUMLStateDiagrams.writeToFile(
          "test",
          LocalActivityStateMachine.class,
          LocalActivityStateMachine.STATE_MACHINE_DEFINITION.asPlantUMLStateDiagramCoverage(
              stateMachineList));
      fail(
          "LocalActivityStateMachine is missing test coverage for the following transitions:\n"
              + missed);
    }
  }

  @Test
  public void testLocalActivityStateMachine() {
    class TestListener extends TestEntityManagerListenerBase {
      Optional<Payloads> result;

      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ExecuteLocalActivityParameters parameters1 =
            new ExecuteLocalActivityParameters(
                PollActivityTaskQueueResponse.newBuilder()
                    .setActivityId("id1")
                    .setActivityType(ActivityType.newBuilder().setName("activity1")),
                null,
                System.currentTimeMillis(),
                null,
                true,
                null,
                null);
        ExecuteLocalActivityParameters parameters2 =
            new ExecuteLocalActivityParameters(
                PollActivityTaskQueueResponse.newBuilder()
                    .setActivityId("id2")
                    .setActivityType(ActivityType.newBuilder().setName("activity2")),
                null,
                System.currentTimeMillis(),
                null,
                false,
                null,
                null);
        ExecuteLocalActivityParameters parameters3 =
            new ExecuteLocalActivityParameters(
                PollActivityTaskQueueResponse.newBuilder()
                    .setActivityId("id3")
                    .setActivityType(ActivityType.newBuilder().setName("activity3")),
                null,
                System.currentTimeMillis(),
                null,
                true,
                null,
                null);

        builder
            .<Optional<Payloads>, LocalActivityCallback.LocalActivityFailedException>add2(
                (r, c) -> stateMachines.scheduleLocalActivityTask(parameters1, c))
            .add((r) -> stateMachines.completeWorkflow(Optional.empty()));

        builder
            .<Optional<Payloads>, LocalActivityCallback.LocalActivityFailedException>add2(
                (r, c) -> stateMachines.scheduleLocalActivityTask(parameters2, c))
            .<Optional<Payloads>, LocalActivityCallback.LocalActivityFailedException>add2(
                (r, c) -> stateMachines.scheduleLocalActivityTask(parameters3, c))
            .add((r) -> result = r.getT1());
      }
    }
    /*
         1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
         2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
         3: EVENT_TYPE_WORKFLOW_TASK_STARTED
         4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
         5: EVENT_TYPE_MARKER_RECORDED
         6: EVENT_TYPE_MARKER_RECORDED
         7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
         8: EVENT_TYPE_WORKFLOW_TASK_STARTED
         9: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
         10: EVENT_TYPE_MARKER_RECORDED
         11: EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
    */
    MarkerRecordedEventAttributes.Builder markerBuilder =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(LocalActivityMarkerUtils.MARKER_NAME)
            .putDetails(MARKER_TIME_KEY, converter.toPayloads(System.currentTimeMillis()).get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(
                        LocalActivityMarkerUtils.MARKER_ACTIVITY_ID_KEY,
                        converter.toPayloads("id2").get())
                    .build())
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(
                        LocalActivityMarkerUtils.MARKER_ACTIVITY_ID_KEY,
                        converter.toPayloads("id3").get())
                    .build())
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(
                        LocalActivityMarkerUtils.MARKER_ACTIVITY_ID_KEY,
                        converter.toPayloads("id1").get())
                    .build())
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    assertEquals(new TestHistoryBuilder.HistoryInfo(0, 3), h.getHistoryInfo(0));
    assertEquals(new TestHistoryBuilder.HistoryInfo(3, 8), h.getHistoryInfo(1));
    assertEquals(new TestHistoryBuilder.HistoryInfo(8, Integer.MAX_VALUE), h.getHistoryInfo());

    TestListener listener = new TestListener();
    stateMachines = newStateMachines(listener);

    {
      h.handleWorkflowTask(stateMachines, 1);
      List<ExecuteLocalActivityParameters> requests = stateMachines.takeLocalActivityRequests();
      assertEquals(2, requests.size());
      assertEquals("id1", requests.get(0).getActivityId());
      assertEquals("id2", requests.get(1).getActivityId());

      Payloads result2 = converter.toPayloads("result2").get();
      LocalActivityResult completionActivity2 =
          new LocalActivityResult(
              "id2",
              1,
              RespondActivityTaskCompletedRequest.newBuilder().setResult(result2).build(),
              null,
              null,
              null);
      stateMachines.handleLocalActivityCompletion(completionActivity2);
      requests = stateMachines.takeLocalActivityRequests();
      assertEquals(1, requests.size());
      assertEquals("id3", requests.get(0).getActivityId());

      Payloads result3 = converter.toPayloads("result3").get();
      LocalActivityResult completionActivity3 =
          new LocalActivityResult(
              "id3",
              1,
              RespondActivityTaskCompletedRequest.newBuilder().setResult(result3).build(),
              null,
              null,
              null);
      stateMachines.handleLocalActivityCompletion(completionActivity3);
      requests = stateMachines.takeLocalActivityRequests();
      assertTrue(requests.isEmpty());

      List<Command> commands = stateMachines.takeCommands();
      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(1).getCommandType());
      Optional<Payloads> dataActivity2 =
          Optional.of(
              commands
                  .get(0)
                  .getRecordMarkerCommandAttributes()
                  .getDetailsMap()
                  .get(MARKER_ACTIVITY_RESULT_KEY));
      assertEquals("result2", converter.fromPayloads(0, dataActivity2, String.class, String.class));
      Optional<Payloads> dataActivity3 =
          Optional.of(
              commands
                  .get(1)
                  .getRecordMarkerCommandAttributes()
                  .getDetailsMap()
                  .get(MARKER_ACTIVITY_RESULT_KEY));
      assertEquals("result3", converter.fromPayloads(0, dataActivity3, String.class, String.class));
    }
    {
      h.handleWorkflowTask(stateMachines, 2);
      List<ExecuteLocalActivityParameters> requests = stateMachines.takeLocalActivityRequests();
      assertTrue(requests.isEmpty());

      Payloads result = converter.toPayloads("result1").get();
      LocalActivityResult completionActivity1 =
          new LocalActivityResult(
              "id1",
              1,
              RespondActivityTaskCompletedRequest.newBuilder().setResult(result).build(),
              null,
              null,
              null);
      stateMachines.handleLocalActivityCompletion(completionActivity1);
      requests = stateMachines.takeLocalActivityRequests();
      assertTrue(requests.isEmpty());
      List<Command> commands = stateMachines.takeCommands();
      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      Optional<Payloads> data =
          Optional.of(
              commands
                  .get(0)
                  .getRecordMarkerCommandAttributes()
                  .getDetailsMap()
                  .get(MARKER_ACTIVITY_RESULT_KEY));
      assertEquals("result1", converter.fromPayloads(0, data, String.class, String.class));
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
      assertEquals(
          "result3", converter.fromPayloads(0, listener.result, String.class, String.class));
    }

    // Test full replay
    {
      listener = new TestListener();
      stateMachines = newStateMachines(listener);

      h.handleWorkflowTask(stateMachines);

      List<Command> commands = stateMachines.takeCommands();
      assertTrue(commands.isEmpty());
    }
  }

  @Test
  public void testLocalActivityStateMachineForcedWorkflowTaskFailure() {
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ExecuteLocalActivityParameters parameters1 =
            new ExecuteLocalActivityParameters(
                PollActivityTaskQueueResponse.newBuilder()
                    .setActivityId("id1")
                    .setActivityType(ActivityType.newBuilder().setName("activity1")),
                null,
                System.currentTimeMillis(),
                null,
                false,
                null,
                null);
        builder
            .<Optional<Payloads>, LocalActivityCallback.LocalActivityFailedException>add2(
                (r, c) -> stateMachines.scheduleLocalActivityTask(parameters1, c))
            .add((r) -> stateMachines.completeWorkflow(Optional.empty()));
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
        8: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        9: EVENT_TYPE_WORKFLOW_TASK_STARTED
        10: EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT
        11: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        12: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask() // forced due to long running local activity
            .addWorkflowTask() // forced due to long running local activity
            .addWorkflowTaskScheduled()
            .addWorkflowTaskStarted()
            .addWorkflowTaskTimedOut()
            .addWorkflowTaskScheduled()
            .addWorkflowTaskStarted();
    assertEquals(new TestHistoryBuilder.HistoryInfo(0, 3), h.getHistoryInfo(0));
    assertEquals(new TestHistoryBuilder.HistoryInfo(3, 6), h.getHistoryInfo(1));
    assertEquals(new TestHistoryBuilder.HistoryInfo(6, 12), h.getHistoryInfo());

    TestListener listener = new TestListener();
    stateMachines = newStateMachines(listener);

    h.handleWorkflowTask(stateMachines);
    List<ExecuteLocalActivityParameters> requests = stateMachines.takeLocalActivityRequests();
    assertEquals(1, requests.size());
    assertEquals("id1", requests.get(0).getActivityId());
    List<Command> commands = stateMachines.takeCommands();
    assertTrue(commands.isEmpty());
  }

  @Test
  public void testLocalActivityStateMachineDuplicateTask() {
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        StartChildWorkflowExecutionParameters childRequest =
            new StartChildWorkflowExecutionParameters(
                StartChildWorkflowExecutionCommandAttributes.newBuilder(),
                ChildWorkflowCancellationType.WAIT_CANCELLATION_REQUESTED,
                null);
        ExecuteLocalActivityParameters parameters1 =
            new ExecuteLocalActivityParameters(
                PollActivityTaskQueueResponse.newBuilder()
                    .setActivityId("id1")
                    .setActivityType(ActivityType.newBuilder().setName("activity1")),
                null,
                System.currentTimeMillis(),
                null,
                false,
                null,
                null);
        // TODO: This is a workaround for the lack of support for child workflow in the test
        // framework.
        // The test framework has no support for state machines with multiple callbacks.
        AtomicReference<Functions.Proc> cc = new AtomicReference<>();
        AtomicReference<Functions.Proc2<Optional<Payloads>, Exception>> completionCallback =
            new AtomicReference<>();
        builder
            .<WorkflowExecution, Exception>add2(
                (r, c) ->
                    cc.set(
                        stateMachines.startChildWorkflow(
                            childRequest,
                            c,
                            (r1, c1) -> {
                              completionCallback.get().apply(r1, c1);
                            })))
            .add((r) -> cc.get().apply())
            .<Optional<Payloads>, Exception>add2(
                (r, c) -> {
                  completionCallback.set(c);
                })
            .<Optional<Payloads>, LocalActivityCallback.LocalActivityFailedException>add2(
                (r, c) -> stateMachines.scheduleLocalActivityTask(parameters1, c));
      }
    }
    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED
        6: EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED
        7: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        8: EVENT_TYPE_WORKFLOW_TASK_STARTED
        9: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        10: EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED
        11: EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED
        12: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        13: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED,
                StartChildWorkflowExecutionInitiatedEventAttributes.newBuilder().build())
            .add(
                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED,
                ChildWorkflowExecutionStartedEventAttributes.newBuilder()
                    .setInitiatedEventId(5)
                    .build())
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED,
                RequestCancelExternalWorkflowExecutionInitiatedEventAttributes.newBuilder().build())
            .addWorkflowTaskScheduled()
            .add(
                EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED,
                ExternalWorkflowExecutionCancelRequestedEventAttributes.newBuilder()
                    .setInitiatedEventId(10)
                    .build())
            .addWorkflowTaskScheduled()
            .addWorkflowTaskStarted();

    TestListener listener = new TestListener();
    stateMachines = newStateMachines(listener);

    h.handleWorkflowTask(stateMachines);
    List<ExecuteLocalActivityParameters> requests = stateMachines.takeLocalActivityRequests();
    assertEquals(1, requests.size());
    assertEquals("id1", requests.get(0).getActivityId());
    List<Command> commands = stateMachines.takeCommands();
    assertTrue(commands.isEmpty());
  }
}
