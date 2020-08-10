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

import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * State machine instance of a single server side entity like activity, workflow task or the whole
 * workflow.
 *
 * @see StateMachineDefinition
 */
final class StateMachine<State, ExplicitEvent, Data> {

  private final StateMachineDefinition<State, ExplicitEvent, Data> definition;

  private State state;

  private final List<Transition<State, TransitionEvent<ExplicitEvent>>> transitionHistory =
      new ArrayList<>();

  /**
   * Create a new instance of the StateMachine.
   *
   * @param definition State machine definition.
   */
  public static <State, ExplicitEvent, Data> StateMachine<State, ExplicitEvent, Data> newInstance(
      StateMachineDefinition<State, ExplicitEvent, Data> definition) {
    return new StateMachine<>(definition);
  }

  private StateMachine(StateMachineDefinition<State, ExplicitEvent, Data> definition) {
    this.definition = Objects.requireNonNull(definition);
    this.state = definition.getInitialState();
  }

  /** All possible history event types that are known to this state machine instance. */
  public Set<EventType> getValidEventTypes() {
    return definition.getValidEventTypes();
  }

  /** Current state of the state machine. */
  public State getState() {
    return state;
  }

  /** Is this state final? */
  public boolean isFinalState() {
    return definition.isFinalState(state);
  }

  /**
   * Applies an explicit event for handling.
   *
   * @param explicitEvent event to handle.
   * @param data data which is passed as an argument to resulting action.
   */
  public void handleExplicitEvent(ExplicitEvent explicitEvent, Data data) {
    executeTransition(new TransitionEvent<>(explicitEvent), data);
  }

  /**
   * Applies an event history event for handling.
   *
   * @param eventType type of the event to handle.
   * @param data data which is passed as an argument to resulting action.
   */
  public void handleHistoryEvent(EventType eventType, Data data) {
    executeTransition(new TransitionEvent<>(eventType), data);
  }

  /**
   * Applies command for handling.
   *
   * @param commandType type of the command to handle.
   * @param data data which is passed as an argument to resulting action.
   */
  public void handleCommand(CommandType commandType, Data data) {
    executeTransition(new TransitionEvent<>(commandType), data);
  }

  public String getHistory() {
    return transitionHistory.toString();
  }

  @Override
  public String toString() {
    return "StateMachine{"
        + "definition="
        + definition
        + ", state="
        + state
        + ", transitionHistory="
        + transitionHistory
        + '}';
  }

  private void executeTransition(TransitionEvent<ExplicitEvent> transitionEvent, Data data) {
    Transition<State, TransitionEvent<ExplicitEvent>> transition =
        new Transition<>(state, transitionEvent);
    TransitionAction<State, Data> destination = definition.getTransitionAction(transition);
    if (destination == null) {
      throw new IllegalArgumentException(
          definition.getName()
              + ": invalid "
              + transition
              + ", transition history is "
              + transitionHistory);
    }
    try {
      state = destination.apply(data);
    } catch (RuntimeException e) {
      throw new RuntimeException(
          definition.getName()
              + ": failure executing "
              + transition
              + ", transition history is "
              + transitionHistory,
          e);
    }
    transitionHistory.add(transition);
  }
}
