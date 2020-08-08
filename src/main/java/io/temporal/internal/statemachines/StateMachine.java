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
import io.temporal.workflow.Functions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * State machine of a single server side entity like activity, workflow task or the whole workflow.
 *
 * <p>Based on the idea that each entity goes through state transitions and the same operation like
 * timeout is applicable to some states only and can lead to different actions in each state. Each
 * valid state transition should be registered through add methods. The associated action is invoked
 * when the state transition is requested.
 *
 * <p>There are three types of events that can cause state transitions:
 *
 * <ul>
 *   <li>Explicit event. The event that is reported through {@link
 *       StateMachine#handleExplicitEvent(Object, Object)}.
 *   <li>History event. This event is caused by processing an event recorded in the event history.
 *       It is reported through {@link StateMachine#handleHistoryEvent(EventType, Object)}.
 *   <li>Command event. This event is caused by reporting that a certain command is prepared to be
 *       sent as part of the workflow task response to the service. It is reported through {@link
 *       StateMachine#handleCommand(CommandType, Object)}.
 * </ul>
 */
final class StateMachine<State, ExplicitEvent, Data> {

  private final List<Transition<State, TransitionEvent<ExplicitEvent>>> transitionHistory =
      new ArrayList<>();
  /** Map of transitions to actions. */
  private final Map<
          Transition<State, TransitionEvent<ExplicitEvent>>, TransitionAction<State, Data>>
      transitions =
          new LinkedHashMap<>(); // linked to maintain the same order for diagram generation

  private final String name;
  private final State initialState;
  private final List<State> finalStates;
  private final Set<EventType> validEventTypes = new HashSet<>();

  private State state;

  /**
   * Create a new instance of the StateMachine.
   *
   * @param name Human readable name of the state machine. Used for logging.
   * @param initialState the initial state the machine is created at.
   * @param finalStates the states at which the state machine cannot make any state transitions and
   *     reports {@link #isFinalState()} as true.
   * @param <State>
   * @param <ExplicitEvent>
   * @param <Data>
   * @return
   */
  public static <State, ExplicitEvent, Data> StateMachine<State, ExplicitEvent, Data> newInstance(
      String name, State initialState, State... finalStates) {
    return new StateMachine<>(name, initialState, finalStates);
  }

  private StateMachine(String name, State initialState, State[] finalStates) {
    this.name = Objects.requireNonNull(name);
    this.initialState = Objects.requireNonNull(initialState);
    this.finalStates = Arrays.asList(finalStates);
    this.state = initialState;
  }

  /** All possible history event types that are known to this state machine instance. */
  public Set<EventType> getValidEventTypes() {
    return validEventTypes;
  }

  /** Current state of the state machine. */
  public State getState() {
    return state;
  }

  /** Is this state final? */
  public boolean isFinalState() {
    return finalStates.contains(state);
  }

  /**
   * Registers a transition between states.
   *
   * @param from initial state that transition applies to
   * @param explicitEvent explicitEvent that caused the transition. Delivered through {@link
   *     #handleExplicitEvent(Object, Object)}.
   * @param to destination state of a transition.
   * @param action action to invoke upon transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachine<State, ExplicitEvent, Data> add(
      State from, ExplicitEvent explicitEvent, State to, Functions.Proc1<Data> action) {
    checkFinalState(from);
    add(
        new Transition<>(from, new TransitionEvent<>(explicitEvent)),
        new FixedTransitionAction<>(to, action));
    return this;
  }

  /**
   * Registers a transition between states.
   *
   * @param from initial state that transition applies to
   * @param explicitEvent explicitEvent that caused the transition. Delivered through {@link
   *     #handleExplicitEvent(Object, Object)}.
   * @param to destination state of a transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachine<State, ExplicitEvent, Data> add(State from, ExplicitEvent explicitEvent, State to) {
    checkFinalState(from);
    add(
        new Transition<>(from, new TransitionEvent<>(explicitEvent)),
        new FixedTransitionAction<>(to, (data) -> {}));
    return this;
  }

  /**
   * Registers a transition between states.
   *
   * @param from initial state that transition applies to
   * @param eventType history event that caused the transition. Delivered through {@link
   *     #handleHistoryEvent(EventType, Object)}.
   * @param to destination state of a transition.
   * @param action action to invoke upon transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachine<State, ExplicitEvent, Data> add(
      State from, EventType eventType, State to, Functions.Proc1<Data> action) {
    checkFinalState(from);
    add(
        new Transition<>(from, new TransitionEvent<>(eventType)),
        new FixedTransitionAction<>(to, action));
    validEventTypes.add(eventType);
    return this;
  }

  /**
   * Registers a transition between states.
   *
   * @param from initial state that transition applies to
   * @param eventType history event that caused the transition. Delivered through {@link
   *     #handleHistoryEvent(EventType, Object)}.
   * @param to destination state of a transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachine<State, ExplicitEvent, Data> add(State from, EventType eventType, State to) {
    checkFinalState(from);
    add(
        new Transition<>(from, new TransitionEvent<>(eventType)),
        new FixedTransitionAction<>(to, (data) -> {}));
    validEventTypes.add(eventType);
    return this;
  }

  /**
   * Registers a dynamic transition between states. Used when the same event can transition to more
   * than one state depending on data.
   *
   * @param from initial state that transition applies to
   * @param eventType history event that caused the transition. Delivered through {@link
   *     #handleHistoryEvent(EventType, Object)}.
   * @param toStates allowed destination states of a transition.
   * @param action action to invoke upon transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachine<State, ExplicitEvent, Data> add(
      State from, EventType eventType, State[] toStates, DynamicCallback<State, Data> action) {
    checkFinalState(from);
    add(
        new Transition<>(from, new TransitionEvent<>(eventType)),
        new DynamicTransitionAction<State, Data>(toStates, action));
    validEventTypes.add(eventType);
    return this;
  }

  /**
   * Registers a transition between states.
   *
   * @param from initial state that transition applies to
   * @param commandType command that caused the transition. Delivered through {@link
   *     #handleCommand(CommandType, Object)}.
   * @param to destination state of a transition.
   * @param action action to invoke upon transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachine<State, ExplicitEvent, Data> add(
      State from, CommandType commandType, State to, Functions.Proc1<Data> action) {
    checkFinalState(from);
    add(
        new Transition<>(from, new TransitionEvent<>(commandType)),
        new FixedTransitionAction<>(to, action));
    return this;
  }

  /**
   * Registers a transition between states.
   *
   * @param from initial state that transition applies to
   * @param commandType command that caused the transition. Delivered through {@link
   *     #handleCommand(CommandType, Object)}.
   * @param to destination state of a transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachine<State, ExplicitEvent, Data> add(State from, CommandType commandType, State to) {
    checkFinalState(from);
    add(
        new Transition<>(from, new TransitionEvent<>(commandType)),
        new FixedTransitionAction<>(to, (data -> {})));
    return this;
  }

  /**
   * Registers a dynamic transition between states. Used when the same explicitEvent can transition
   * to more than one state depending on data.
   *
   * @param from initial state that transition applies to
   * @param toStates allowed destination states of a transition.
   * @param action action to invoke upon transition
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachine<State, ExplicitEvent, Data> add(
      State from,
      ExplicitEvent explicitEvent,
      State[] toStates,
      DynamicCallback<State, Data> action) {
    checkFinalState(from);
    add(
        new Transition<>(from, new TransitionEvent<>(explicitEvent)),
        new DynamicTransitionAction<>(toStates, action));
    return this;
  }

  /**
   * Applies an explicit event for handling.
   *
   * @param explicitEvent event to handle.
   * @param data data which is passed as an argument to resulting action.
   */
  public void handleExplicitEvent(ExplicitEvent explicitEvent, Data data) {
    explicitEvent(new TransitionEvent<>(explicitEvent), data);
  }

  /**
   * Applies an event history event for handling.
   *
   * @param eventType type of the event to handle.
   * @param data data which is passed as an argument to resulting action.
   */
  public void handleHistoryEvent(EventType eventType, Data data) {
    explicitEvent(new TransitionEvent<>(eventType), data);
  }

  /**
   * Applies command for handling.
   *
   * @param commandType type of the command to handle.
   * @param data data which is passed as an argument to resulting action.
   */
  public void handleCommand(CommandType commandType, Data data) {
    explicitEvent(new TransitionEvent<>(commandType), data);
  }

  public String getHistory() {
    return transitionHistory.toString();
  }

  @Override
  public String toString() {
    return "StateMachine{"
        + "name='"
        + name
        + '\''
        + ", state="
        + state
        + ", transitionHistory="
        + transitionHistory
        + '}';
  }

  private void checkFinalState(State from) {
    if (finalStates.contains(from)) {
      throw new IllegalArgumentException("State transition from a final state is not allowed");
    }
  }

  private void add(
      Transition<State, TransitionEvent<ExplicitEvent>> transition,
      TransitionAction<State, Data> target) {
    if (transitions.containsKey(transition)) {
      throw new IllegalArgumentException("Duplicated transition is not allowed: " + transition);
    }
    transitions.put(transition, target);
  }

  private void explicitEvent(TransitionEvent<ExplicitEvent> transitionEvent, Data data) {
    Transition<State, TransitionEvent<ExplicitEvent>> transition =
        new Transition<>(state, transitionEvent);
    TransitionAction<State, Data> destination = transitions.get(transition);
    if (destination == null) {
      throw new IllegalArgumentException(
          name + ": invalid " + transition + ", transition history is " + transitionHistory);
    }
    try {
      state = destination.apply(data);
    } catch (RuntimeException e) {
      throw new RuntimeException(
          name
              + ": failure executing "
              + transition
              + ", transition history is "
              + transitionHistory,
          e);
    }
    transitionHistory.add(transition);
  }

  /**
   * Generates PlantUML (plantuml.com/state-diagram) diagram representation of the state machine.
   */
  public String asPlantUMLStateDiagram() {
    StringBuilder result = new StringBuilder();
    result.append("@startuml\n" + "scale 350 width\n");
    result.append("[*] --> ");
    result.append(initialState);
    result.append('\n');
    for (Map.Entry<Transition<State, TransitionEvent<ExplicitEvent>>, TransitionAction<State, Data>>
        entry : transitions.entrySet()) {
      List<State> targets = entry.getValue().getAllowedStates();
      for (State target : targets) {
        result.append(entry.getKey().getFrom());
        result.append(" --> ");
        result.append(target);
        result.append(": ");
        result.append(entry.getKey().getExplicitEvent());
        result.append('\n');
      }
    }
    for (State finalState : finalStates) {
      result.append(finalState);
      result.append(" --> [*]\n");
    }
    result.append("@enduml\n");
    return result.toString();
  }
}
