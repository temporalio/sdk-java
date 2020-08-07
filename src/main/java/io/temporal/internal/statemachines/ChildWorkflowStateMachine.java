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

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.history.v1.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionTerminatedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.api.history.v1.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.common.converter.EncodedValues;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.failure.TerminatedFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.internal.replay.ChildWorkflowTaskFailedException;
import io.temporal.workflow.Functions;
import java.util.Optional;

final class ChildWorkflowStateMachine
    extends EntityStateMachineInitialCommand<
        ChildWorkflowStateMachine.State,
        ChildWorkflowStateMachine.Action,
        ChildWorkflowStateMachine> {

  enum Action {
    SCHEDULE,
    CANCEL
  }

  enum State {
    CREATED,
    START_COMMAND_CREATED,
    START_EVENT_RECORDED,
    STARTED,
    START_FAILED,
    COMPLETED,
    FAILED,
    CANCELED,
    TIMED_OUT,
    TERMINATED,
  }

  private static StateMachine<State, Action, ChildWorkflowStateMachine> newStateMachine() {
    return StateMachine.<State, Action, ChildWorkflowStateMachine>newInstance(
            "ChildWorkflow",
            State.CREATED,
            State.START_FAILED,
            State.COMPLETED,
            State.FAILED,
            State.CANCELED,
            State.TIMED_OUT,
            State.TERMINATED)
        .add(
            State.CREATED,
            Action.SCHEDULE,
            State.START_COMMAND_CREATED,
            ChildWorkflowStateMachine::createStartChildCommand)
        .add(
            State.START_COMMAND_CREATED,
            CommandType.COMMAND_TYPE_START_CHILD_WORKFLOW_EXECUTION,
            State.START_COMMAND_CREATED)
        .add(
            State.START_COMMAND_CREATED,
            EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED,
            State.START_EVENT_RECORDED)
        .add(
            State.START_COMMAND_CREATED,
            Action.CANCEL,
            State.CANCELED,
            ChildWorkflowStateMachine::cancelStartChildCommand)
        .add(
            State.START_EVENT_RECORDED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED,
            State.STARTED,
            ChildWorkflowStateMachine::notifyStarted)
        .add(
            State.START_EVENT_RECORDED,
            EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED,
            State.START_FAILED,
            ChildWorkflowStateMachine::notifyStartFailed)
        .add(
            State.STARTED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED,
            State.COMPLETED,
            ChildWorkflowStateMachine::notifyCompleted)
        .add(
            State.STARTED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED,
            State.FAILED,
            ChildWorkflowStateMachine::notifyFailed)
        .add(
            State.STARTED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT,
            State.TIMED_OUT,
            ChildWorkflowStateMachine::notifyTimedOut)
        .add(
            State.STARTED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED,
            State.CANCELED,
            ChildWorkflowStateMachine::notifyCanceled)
        .add(
            State.STARTED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED,
            State.TERMINATED,
            ChildWorkflowStateMachine::notifyTerminated);
  }

  private final StartChildWorkflowExecutionCommandAttributes startAttributes;

  private final Functions.Proc1<WorkflowExecution> startedCallback;

  private final Functions.Proc2<Optional<Payloads>, Exception> completionCallback;

  /**
   * Creates a new child workflow state machine
   *
   * @param attributes child workflow start command attributes
   * @param startedCallback
   * @param completionCallback invoked when child reports completion or failure. The following types
   *     of events can be passed to the callback: StartChildWorkflowExecutionFailedEvent,
   *     ChildWorkflowExecutionCompletedEvent, ChildWorkflowExecutionFailedEvent,
   *     ChildWorkflowExecutionTimedOutEvent, ChildWorkflowExecutionCanceledEvent,
   *     ChildWorkflowExecutionTerminatedEvent.
   * @return cancellation callback that should be invoked to cancel the child
   */
  public static ChildWorkflowStateMachine newInstance(
      StartChildWorkflowExecutionCommandAttributes attributes,
      Functions.Proc1<WorkflowExecution> startedCallback,
      Functions.Proc2<Optional<Payloads>, Exception> completionCallback,
      Functions.Proc1<NewCommand> commandSink) {
    return new ChildWorkflowStateMachine(
        attributes, startedCallback, completionCallback, commandSink);
  }

  private ChildWorkflowStateMachine(
      StartChildWorkflowExecutionCommandAttributes startAttributes,
      Functions.Proc1<WorkflowExecution> startedCallback,
      Functions.Proc2<Optional<Payloads>, Exception> completionCallback,
      Functions.Proc1<NewCommand> commandSink) {
    super(newStateMachine(), commandSink);
    this.startAttributes = startAttributes;
    this.startedCallback = startedCallback;
    this.completionCallback = completionCallback;
    action(Action.SCHEDULE);
  }

  public void createStartChildCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_START_CHILD_WORKFLOW_EXECUTION)
            .setStartChildWorkflowExecutionCommandAttributes(startAttributes)
            .build());
  }

  public boolean isCancellable() {
    return State.START_COMMAND_CREATED == getState();
  }

  /**
   * Cancellation through this class is valid only when start child workflow command is not sent
   * yet. Cancellation of an initiated child workflow is done through CancelExternalCommands. So all
   * of the types besides ABANDON are treated differently.
   */
  public void cancel() {
    action(Action.CANCEL);
  }

  private void cancelStartChildCommand() {
    cancelInitialCommand();
    CanceledFailure failure = new CanceledFailure("Child canceled", null, null);
    completionCallback.apply(Optional.empty(), failure);
  }

  private void notifyCompleted() {
    ChildWorkflowExecutionCompletedEventAttributes attributes =
        currentEvent.getChildWorkflowExecutionCompletedEventAttributes();
    Optional<Payloads> result =
        attributes.hasResult() ? Optional.of(attributes.getResult()) : Optional.empty();
    completionCallback.apply(result, null);
  }

  private void notifyStartFailed() {
    StartChildWorkflowExecutionFailedEventAttributes attributes =
        currentEvent.getStartChildWorkflowExecutionFailedEventAttributes();
    Exception failure =
        new ChildWorkflowTaskFailedException(
            currentEvent.getEventId(),
            WorkflowExecution.newBuilder().setWorkflowId(attributes.getWorkflowId()).build(),
            attributes.getWorkflowType(),
            RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
            null);
    failure.initCause(
        new WorkflowExecutionAlreadyStarted(
            WorkflowExecution.newBuilder().setWorkflowId(attributes.getWorkflowId()).build(),
            attributes.getWorkflowType().getName(),
            null));
    completionCallback.apply(Optional.empty(), failure);
  }

  private void notifyFailed() {
    ChildWorkflowExecutionFailedEventAttributes attributes =
        currentEvent.getChildWorkflowExecutionFailedEventAttributes();
    RuntimeException failure =
        new ChildWorkflowTaskFailedException(
            currentEvent.getEventId(),
            attributes.getWorkflowExecution(),
            attributes.getWorkflowType(),
            attributes.getRetryState(),
            attributes.getFailure());
    completionCallback.apply(Optional.empty(), failure);
  }

  private void notifyTimedOut() {
    ChildWorkflowExecutionTimedOutEventAttributes attributes =
        currentEvent.getChildWorkflowExecutionTimedOutEventAttributes();
    TimeoutFailure timeoutFailure =
        new TimeoutFailure(null, null, TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE);
    timeoutFailure.setStackTrace(new StackTraceElement[0]);
    RuntimeException failure =
        new ChildWorkflowFailure(
            attributes.getInitiatedEventId(),
            attributes.getStartedEventId(),
            attributes.getWorkflowType().getName(),
            attributes.getWorkflowExecution(),
            attributes.getNamespace(),
            attributes.getRetryState(),
            timeoutFailure);
    completionCallback.apply(Optional.empty(), failure);
  }

  private void notifyCanceled() {
    ChildWorkflowExecutionCanceledEventAttributes attributes =
        currentEvent.getChildWorkflowExecutionCanceledEventAttributes();
    CanceledFailure failure =
        new CanceledFailure("Child canceled", new EncodedValues(attributes.getDetails()), null);
    completionCallback.apply(Optional.empty(), failure);
  }

  private void notifyTerminated() {
    ChildWorkflowExecutionTerminatedEventAttributes attributes =
        currentEvent.getChildWorkflowExecutionTerminatedEventAttributes();
    RuntimeException failure =
        new ChildWorkflowFailure(
            attributes.getInitiatedEventId(),
            attributes.getStartedEventId(),
            attributes.getWorkflowType().getName(),
            attributes.getWorkflowExecution(),
            attributes.getNamespace(),
            RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
            new TerminatedFailure(null, null));
    completionCallback.apply(Optional.empty(), failure);
  }

  private void notifyStarted() {
    startedCallback.apply(
        currentEvent.getChildWorkflowExecutionStartedEventAttributes().getWorkflowExecution());
  }

  public static String asPlantUMLStateDiagram() {
    return newStateMachine().asPlantUMLStateDiagram();
  }
}
