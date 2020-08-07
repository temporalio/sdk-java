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

import static io.temporal.internal.statemachines.LocalActivityStateMachine.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.api.command.v1.Command;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.replay.ExecuteLocalActivityParameters;
import io.temporal.internal.worker.ActivityTaskHandler;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class LocalActivityStateMachineTest {

  private final DataConverter converter = DataConverter.getDefaultInstance();
  private WorkflowStateMachines manager;

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
                    .setActivityType(ActivityType.newBuilder().setName("activity1")));
        ExecuteLocalActivityParameters parameters2 =
            new ExecuteLocalActivityParameters(
                PollActivityTaskQueueResponse.newBuilder()
                    .setActivityId("id2")
                    .setActivityType(ActivityType.newBuilder().setName("activity2")));
        ExecuteLocalActivityParameters parameters3 =
            new ExecuteLocalActivityParameters(
                PollActivityTaskQueueResponse.newBuilder()
                    .setActivityId("id3")
                    .setActivityType(ActivityType.newBuilder().setName("activity3")));

        builder
            .<Optional<Payloads>, Failure>add2(
                (r, c) -> manager.scheduleLocalActivityTask(parameters1, c))
            .add((r) -> manager.newCompleteWorkflow(Optional.empty()));

        builder
            .<Optional<Payloads>, Failure>add2(
                (r, c) -> manager.scheduleLocalActivityTask(parameters2, c))
            .<Optional<Payloads>, Failure>add2(
                (r, c) -> manager.scheduleLocalActivityTask(parameters3, c))
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
            .setMarkerName(LOCAL_ACTIVITY_MARKER_NAME)
            .putDetails(MARKER_TIME_KEY, converter.toPayloads(System.currentTimeMillis()).get());
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_ACTIVITY_ID_KEY, converter.toPayloads("id2").get())
                    .build())
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_ACTIVITY_ID_KEY, converter.toPayloads("id3").get())
                    .build())
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_MARKER_RECORDED,
                markerBuilder
                    .putDetails(MARKER_ACTIVITY_ID_KEY, converter.toPayloads("id1").get())
                    .build())
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED);
    assertEquals(new TestHistoryBuilder.HistoryInfo(0, 3), h.getHistoryInfo(1));
    assertEquals(new TestHistoryBuilder.HistoryInfo(3, 8), h.getHistoryInfo(2));
    assertEquals(new TestHistoryBuilder.HistoryInfo(3, 8), h.getHistoryInfo());

    TestListener listener = new TestListener();
    manager = new WorkflowStateMachines(listener);

    {
      h.handleWorkflowTask(manager, 1);
      List<ExecuteLocalActivityParameters> requests = manager.takeLocalActivityRequests();
      assertEquals(2, requests.size());
      assertEquals("id1", requests.get(0).getActivityTask().getActivityId());
      assertEquals("id2", requests.get(1).getActivityTask().getActivityId());

      Payloads result2 = converter.toPayloads("result2").get();
      ActivityTaskHandler.Result completionActivity2 =
          new ActivityTaskHandler.Result(
              "id2",
              RespondActivityTaskCompletedRequest.newBuilder().setResult(result2).build(),
              null,
              null,
              null);
      manager.handleLocalActivityCompletion(completionActivity2);
      requests = manager.takeLocalActivityRequests();
      assertEquals(1, requests.size());
      assertEquals("id3", requests.get(0).getActivityTask().getActivityId());

      Payloads result3 = converter.toPayloads("result3").get();
      ActivityTaskHandler.Result completionActivity3 =
          new ActivityTaskHandler.Result(
              "id3",
              RespondActivityTaskCompletedRequest.newBuilder().setResult(result3).build(),
              null,
              null,
              null);
      manager.handleLocalActivityCompletion(completionActivity3);
      requests = manager.takeLocalActivityRequests();
      assertTrue(requests.isEmpty());

      List<Command> commands = manager.takeCommands();
      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(1).getCommandType());
      Optional<Payloads> dataActivity2 =
          Optional.of(
              commands
                  .get(0)
                  .getRecordMarkerCommandAttributes()
                  .getDetailsMap()
                  .get(MARKER_DATA_KEY));
      assertEquals("result2", converter.fromPayloads(0, dataActivity2, String.class, String.class));
      Optional<Payloads> dataActivity3 =
          Optional.of(
              commands
                  .get(1)
                  .getRecordMarkerCommandAttributes()
                  .getDetailsMap()
                  .get(MARKER_DATA_KEY));
      assertEquals("result3", converter.fromPayloads(0, dataActivity3, String.class, String.class));
    }
    {
      h.handleWorkflowTask(manager, 2);
      List<ExecuteLocalActivityParameters> requests = manager.takeLocalActivityRequests();
      assertTrue(requests.isEmpty());

      Payloads result = converter.toPayloads("result1").get();
      ActivityTaskHandler.Result completionActivity1 =
          new ActivityTaskHandler.Result(
              "id1",
              RespondActivityTaskCompletedRequest.newBuilder().setResult(result).build(),
              null,
              null,
              null);
      manager.handleLocalActivityCompletion(completionActivity1);
      requests = manager.takeLocalActivityRequests();
      assertTrue(requests.isEmpty());
      List<Command> commands = manager.takeCommands();
      assertEquals(2, commands.size());
      assertEquals(CommandType.COMMAND_TYPE_RECORD_MARKER, commands.get(0).getCommandType());
      Optional<Payloads> data =
          Optional.of(
              commands
                  .get(0)
                  .getRecordMarkerCommandAttributes()
                  .getDetailsMap()
                  .get(MARKER_DATA_KEY));
      assertEquals("result1", converter.fromPayloads(0, data, String.class, String.class));
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(1).getCommandType());
      assertEquals(
          "result3", converter.fromPayloads(0, listener.result, String.class, String.class));
    }

    // Test full replay
    {
      listener = new TestListener();
      manager = new WorkflowStateMachines(listener);

      h.handleWorkflowTask(manager);

      List<Command> commands = manager.takeCommands();
      assertTrue(commands.isEmpty());
    }
  }

  @Test
  public void testLocalActivityStateMachineForcedWorkflowTaskFailure() {
    class TestListener extends TestEntityManagerListenerBase {
      Optional<Payloads> result;

      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        ExecuteLocalActivityParameters parameters1 =
            new ExecuteLocalActivityParameters(
                PollActivityTaskQueueResponse.newBuilder()
                    .setActivityId("id1")
                    .setActivityType(ActivityType.newBuilder().setName("activity1")));
        builder
            .<Optional<Payloads>, Failure>add2(
                (r, c) -> manager.scheduleLocalActivityTask(parameters1, c))
            .add((r) -> manager.newCompleteWorkflow(Optional.empty()));
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
    MarkerRecordedEventAttributes.Builder markerBuilder =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(LOCAL_ACTIVITY_MARKER_NAME)
            .putDetails(MARKER_TIME_KEY, converter.toPayloads(System.currentTimeMillis()).get());
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
    assertEquals(new TestHistoryBuilder.HistoryInfo(0, 3), h.getHistoryInfo(1));
    assertEquals(new TestHistoryBuilder.HistoryInfo(3, 6), h.getHistoryInfo(2));
    assertEquals(new TestHistoryBuilder.HistoryInfo(6, 12), h.getHistoryInfo());

    TestListener listener = new TestListener();
    manager = new WorkflowStateMachines(listener);

    h.handleWorkflowTask(manager);
    List<ExecuteLocalActivityParameters> requests = manager.takeLocalActivityRequests();
    assertEquals(1, requests.size());
    assertEquals("id1", requests.get(0).getActivityTask().getActivityId());
    List<Command> commands = manager.takeCommands();
    assertTrue(commands.isEmpty());
  }
}
