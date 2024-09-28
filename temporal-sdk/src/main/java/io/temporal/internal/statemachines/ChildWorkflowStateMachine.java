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
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.common.converter.EncodedValues;
import io.temporal.failure.*;
import io.temporal.internal.replay.ChildWorkflowTaskFailedException;
import io.temporal.workflow.Functions;
import java.util.Optional;

final class ChildWorkflowStateMachine
    extends EntityStateMachineInitialCommand<
        ChildWorkflowStateMachine.State,
        ChildWorkflowStateMachine.ExplicitEvent,
        ChildWorkflowStateMachine> {

  private String workflowType;
  private String namespace;
  private String workflowId;

  enum ExplicitEvent {
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

  public static final StateMachineDefinition<State, ExplicitEvent, ChildWorkflowStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, ChildWorkflowStateMachine>newInstance(
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
                  ExplicitEvent.SCHEDULE,
                  State.START_COMMAND_CREATED,
                  ChildWorkflowStateMachine::createStartChildCommand)
              .add(
                  State.START_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_START_CHILD_WORKFLOW_EXECUTION,
                  State.START_COMMAND_CREATED)
              .add(
                  State.START_COMMAND_CREATED,
                  EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED,
                  State.START_EVENT_RECORDED,
                  EntityStateMachineInitialCommand::setInitialCommandEventId)
              .add(
                  State.START_COMMAND_CREATED,
                  ExplicitEvent.CANCEL,
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

  private StartChildWorkflowExecutionCommandAttributes startAttributes;

  private UserMetadata metadata;

  private final Functions.Proc2<WorkflowExecution, Exception> startedCallback;

  private final Functions.Proc2<Optional<Payloads>, Exception> completionCallback;

  /**
   * Creates a new child workflow state machine
   *
   * @param attributes child workflow start command attributes
   * @param metadata user metadata to be associated with the child workflow
   * @param startedCallback callback that is notified about child start
   * @param completionCallback invoked when child reports completion or failure
   * @return cancellation callback that should be invoked to cancel the child
   */
  public static ChildWorkflowStateMachine newInstance(
      StartChildWorkflowExecutionCommandAttributes attributes,
      UserMetadata metadata,
      Functions.Proc2<WorkflowExecution, Exception> startedCallback,
      Functions.Proc2<Optional<Payloads>, Exception> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    return new ChildWorkflowStateMachine(
        attributes, metadata, startedCallback, completionCallback, commandSink, stateMachineSink);
  }

  private ChildWorkflowStateMachine(
      StartChildWorkflowExecutionCommandAttributes startAttributes,
      UserMetadata metadata,
      Functions.Proc2<WorkflowExecution, Exception> startedCallback,
      Functions.Proc2<Optional<Payloads>, Exception> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.startAttributes = startAttributes;
    this.metadata = metadata;
    this.workflowType = startAttributes.getWorkflowType().getName();
    this.namespace = startAttributes.getNamespace();
    this.workflowId = startAttributes.getWorkflowId();
    this.startedCallback = startedCallback;
    this.completionCallback = completionCallback;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  public void createStartChildCommand() {
    Command.Builder command =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_START_CHILD_WORKFLOW_EXECUTION)
            .setStartChildWorkflowExecutionCommandAttributes(startAttributes);

    if (metadata != null) {
      command.setUserMetadata(metadata);
      metadata = null;
    }
    addCommand(command.build());
    startAttributes = null; // avoiding retaining large input for the duration of the child
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
    if (!isFinalState()) {
      explicitEvent(ExplicitEvent.CANCEL);
    }
  }

  private void cancelStartChildCommand() {
    cancelCommand();
    RuntimeException failure =
        new ChildWorkflowFailure(
            0,
            0,
            workflowType,
            WorkflowExecution.newBuilder().setWorkflowId(workflowId).build(),
            namespace,
            RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
            new CanceledFailure("Child immediately canceled"));
    startedCallback.apply(null, failure);
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
    // TODO should use attributes.startChildWorkflowExecutionFailedCause here and add a handling for
    // NAMESPACE_NOT_FOUND
    WorkflowException cause =
        new WorkflowExecutionAlreadyStarted(
            WorkflowExecution.newBuilder().setWorkflowId(attributes.getWorkflowId()).build(),
            attributes.getWorkflowType().getName(),
            null);
    RuntimeException failure =
        new ChildWorkflowFailure(
            attributes.getInitiatedEventId(),
            0,
            attributes.getWorkflowType().getName(),
            WorkflowExecution.newBuilder().setWorkflowId(attributes.getWorkflowId()).build(),
            attributes.getNamespace(),
            RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
            cause);

    startedCallback.apply(null, failure);
    completionCallback.apply(Optional.empty(), failure);
  }

  private void notifyFailed() {
    ChildWorkflowExecutionFailedEventAttributes attributes =
        currentEvent.getChildWorkflowExecutionFailedEventAttributes();
    ChildWorkflowFailure exception =
        new ChildWorkflowFailure(
            attributes.getInitiatedEventId(),
            attributes.getStartedEventId(),
            attributes.getWorkflowType().getName(),
            attributes.getWorkflowExecution(),
            attributes.getNamespace(),
            attributes.getRetryState(),
            null);
    RuntimeException failure =
        new ChildWorkflowTaskFailedException(exception, attributes.getFailure());
    completionCallback.apply(Optional.empty(), failure);
  }

  private void notifyTimedOut() {
    ChildWorkflowExecutionTimedOutEventAttributes attributes =
        currentEvent.getChildWorkflowExecutionTimedOutEventAttributes();
    TimeoutFailure timeoutFailure =
        new TimeoutFailure(null, null, TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE);
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
    RuntimeException failure =
        new ChildWorkflowFailure(
            attributes.getInitiatedEventId(),
            attributes.getStartedEventId(),
            attributes.getWorkflowType().getName(),
            attributes.getWorkflowExecution(),
            attributes.getNamespace(),
            RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
            new CanceledFailure(
                "Child canceled", new EncodedValues(attributes.getDetails()), null));
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
        currentEvent.getChildWorkflowExecutionStartedEventAttributes().getWorkflowExecution(),
        null);
  }
}
