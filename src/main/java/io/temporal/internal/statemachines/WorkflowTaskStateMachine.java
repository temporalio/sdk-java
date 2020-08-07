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

import com.google.protobuf.util.Timestamps;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.history.v1.WorkflowTaskFailedEventAttributes;
import java.util.Objects;

final class WorkflowTaskStateMachine
    extends EntityStateMachineInitialCommand<
        WorkflowTaskStateMachine.State, WorkflowTaskStateMachine.Action, WorkflowTaskStateMachine> {

  public interface Listener {
    /**
     * Called for each WorkflowTaskStarted event.
     *
     * @param startEventId eventId of the WorkflowTaskStarted event.
     * @param currentTimeMillis time of the workflow taken from the WorkflowTaskStarted event
     *     timestamp.
     * @param nonProcessedWorkflowTask true if the task is the task that wasn't processed. During
     *     workflow execution this is the last event in the history. During replay (due to query for
     *     example) the last workflow task still can return false if it is followed by other events
     *     like WorkflowExecutionCompleted.
     */
    void workflowTaskStarted(
        long startEventId, long currentTimeMillis, boolean nonProcessedWorkflowTask);

    void updateRunId(String currentRunId);
  }

  private final long workflowTaskStartedEventId;
  private final Listener listener;

  private long currentTimeMillis;
  private long startedEventId;

  public static WorkflowTaskStateMachine newInstance(
      long workflowTaskStartedEventId, Listener listener) {
    return new WorkflowTaskStateMachine(workflowTaskStartedEventId, listener);
  }

  private WorkflowTaskStateMachine(long workflowTaskStartedEventId, Listener listener) {
    super(
        newStateMachine(),
        (c) -> {
          throw new UnsupportedOperationException("doesn't generate commands");
        });
    this.workflowTaskStartedEventId = workflowTaskStartedEventId;
    this.listener = Objects.requireNonNull(listener);
  }

  enum Action {}

  enum State {
    SCHEDULED,
    STARTED,
    COMPLETED,
    TIMED_OUT,
    FAILED,
  }

  private static StateMachine<State, Action, WorkflowTaskStateMachine> newStateMachine() {
    return StateMachine.<State, Action, WorkflowTaskStateMachine>newInstance(
            "WorkflowTask", State.SCHEDULED, State.COMPLETED, State.TIMED_OUT, State.FAILED)
        .add(
            State.SCHEDULED,
            EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED,
            State.STARTED,
            WorkflowTaskStateMachine::handleStarted)
        .add(
            State.STARTED,
            EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED,
            State.COMPLETED,
            WorkflowTaskStateMachine::handleCompleted)
        .add(
            State.STARTED,
            EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED,
            State.FAILED,
            WorkflowTaskStateMachine::handleFailed)
        .add(State.STARTED, EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT, State.TIMED_OUT);
  }

  private void handleStarted() {
    currentTimeMillis = Timestamps.toMillis(currentEvent.getEventTime());
    startedEventId = currentEvent.getEventId();
    // The last started event in the history. So no completed is expected.
    if (currentEvent.getEventId() >= workflowTaskStartedEventId && !hasNextEvent) {
      handleCompleted();
    }
  }

  /** Only update current time if a decision task has completed successfully. */
  private void handleCompleted() {
    boolean lastTaskInHistory =
        currentEvent.getEventId() >= workflowTaskStartedEventId && !hasNextEvent;
    listener.workflowTaskStarted(startedEventId, currentTimeMillis, lastTaskInHistory);
  }

  private void handleFailed() {
    // Reset creates a new run of a workflow. The tricky part is that that the replay
    // of the reset workflow has to use the original runId up to the reset point to
    // maintain the same results. This code resets the id to the new one after the reset to
    // ensure that the new random and UUID are generated form this point.
    WorkflowTaskFailedEventAttributes attr = currentEvent.getWorkflowTaskFailedEventAttributes();
    if (attr.getCause() == WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_RESET_WORKFLOW) {
      this.listener.updateRunId(attr.getNewRunId());
    }
  }

  public static String asPlantUMLStateDiagram() {
    return newStateMachine().asPlantUMLStateDiagram();
  }
}
