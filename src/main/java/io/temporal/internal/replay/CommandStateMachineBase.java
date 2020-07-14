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

package io.temporal.internal.replay;

import io.temporal.api.history.v1.HistoryEvent;
import java.util.ArrayList;
import java.util.List;

abstract class CommandStateMachineBase implements CommandStateMachine {

  protected CommandState state = CommandState.CREATED;

  protected List<String> stateHistory = new ArrayList<String>();

  private final CommandId id;

  public CommandStateMachineBase(CommandId id) {
    this.id = id;
    stateHistory.add(state.toString());
  }

  /** Used for unit testing. */
  protected CommandStateMachineBase(CommandId id, CommandState state) {
    this.id = id;
    this.state = state;
    stateHistory.add(state.toString());
  }

  @Override
  public CommandState getState() {
    return state;
  }

  @Override
  public CommandId getId() {
    return id;
  }

  @Override
  public boolean isDone() {
    return state == CommandState.COMPLETED
        || state == CommandState.COMPLETED_AFTER_CANCELLATION_COMMAND_SENT;
  }

  @Override
  public void handleWorkflowTaskStartedEvent() {
    switch (state) {
      case CREATED:
        stateHistory.add("handleWorkflowTaskStartedEvent");
        state = CommandState.COMMAND_SENT;
        stateHistory.add(state.toString());
        break;
      default:
    }
  }

  @Override
  public boolean cancel(Runnable immediateCancellationCallback) {
    stateHistory.add("cancel");
    boolean result = false;
    switch (state) {
      case CREATED:
        state = CommandState.COMPLETED;
        if (immediateCancellationCallback != null) {
          immediateCancellationCallback.run();
        }
        break;
      case COMMAND_SENT:
        state = CommandState.CANCELED_BEFORE_INITIATED;
        result = true;
        break;
      case INITIATED:
        state = CommandState.CANCELED_AFTER_INITIATED;
        result = true;
        break;
      default:
        failStateTransition();
    }
    stateHistory.add(state.toString());
    return result;
  }

  @Override
  public void handleInitiatedEvent(HistoryEvent event) {
    stateHistory.add("handleInitiatedEvent");
    switch (state) {
      case COMMAND_SENT:
        state = CommandState.INITIATED;
        break;
      case CANCELED_BEFORE_INITIATED:
        state = CommandState.CANCELED_AFTER_INITIATED;
        break;
      default:
        failStateTransition();
    }
    stateHistory.add(state.toString());
  }

  @Override
  public void handleInitiationFailedEvent(HistoryEvent event) {
    stateHistory.add("handleInitiationFailedEvent");
    switch (state) {
      case INITIATED:
      case COMMAND_SENT:
      case CANCELED_BEFORE_INITIATED:
        state = CommandState.COMPLETED;
        break;
      default:
        failStateTransition();
    }
    stateHistory.add(state.toString());
  }

  @Override
  public void handleStartedEvent(HistoryEvent event) {
    stateHistory.add("handleStartedEvent");
  }

  @Override
  public void handleCompletionEvent() {
    stateHistory.add("handleCompletionEvent");
    switch (state) {
      case CANCELED_AFTER_INITIATED:
      case INITIATED:
        state = CommandState.COMPLETED;
        break;
      case CANCELLATION_COMMAND_SENT:
        state = CommandState.COMPLETED_AFTER_CANCELLATION_COMMAND_SENT;
        break;
      default:
        failStateTransition();
    }
    stateHistory.add(state.toString());
  }

  @Override
  public void handleCancellationInitiatedEvent() {
    stateHistory.add("handleCancellationInitiatedEvent");
    switch (state) {
      case CANCELLATION_COMMAND_SENT:
        // No state change
        break;
      default:
        failStateTransition();
    }
    stateHistory.add(state.toString());
  }

  @Override
  public void handleCancellationFailureEvent(HistoryEvent event) {
    stateHistory.add("handleCancellationFailureEvent");
    switch (state) {
      case COMPLETED_AFTER_CANCELLATION_COMMAND_SENT:
        state = CommandState.COMPLETED;
        break;
      default:
        failStateTransition();
    }
    stateHistory.add(state.toString());
  }

  @Override
  public void handleCancellationEvent() {
    stateHistory.add("handleCancellationEvent");
    switch (state) {
      case CANCELLATION_COMMAND_SENT:
        state = CommandState.COMPLETED;
        break;
      default:
        failStateTransition();
    }
    stateHistory.add(state.toString());
  }

  @Override
  public String toString() {
    return "CommandStateMachineBase [id="
        + id
        + ", state="
        + state
        + ", isDone="
        + isDone()
        + ", stateHistory="
        + stateHistory
        + "]";
  }

  protected void failStateTransition() {
    throw new IllegalStateException("id=" + id + ", transitions=" + stateHistory);
  }
}
