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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * State machine definition of a single server side entity like activity, workflow task or the whole
 * workflow.
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
final class StateMachineDefinition<State, ExplicitEvent, Data> {

  /** Map of transitions to actions. */
  private final Map<
          Transition<State, TransitionEvent<ExplicitEvent>>, TransitionAction<State, Data>>
      transitions =
          new LinkedHashMap<>(); // linked to maintain the same order for diagram generation

  private final String name;
  private final State initialState;
  private final List<State> finalStates;
  private final Set<EventType> validEventTypes = new HashSet<>();

  /**
   * Create a new instance of the StateMachine.
   *
   * @param name Human readable name of the state machine. Used for logging.
   * @param initialState the initial state the machine is created at.
   * @param finalStates the states at which the state machine cannot make any state transitions and
   *     reports {@link StateMachine#isFinalState()} as true.
   * @param <State>
   * @param <ExplicitEvent>
   * @param <Data>
   * @return
   */
  public static <State, ExplicitEvent, Data>
      StateMachineDefinition<State, ExplicitEvent, Data> newInstance(
          String name, State initialState, State... finalStates) {
    return new StateMachineDefinition<>(name, initialState, finalStates);
  }

  private StateMachineDefinition(String name, State initialState, State[] finalStates) {
    this.name = Objects.requireNonNull(name);
    this.initialState = Objects.requireNonNull(initialState);
    this.finalStates = Arrays.asList(finalStates);
  }

  public String getName() {
    return name;
  }

  public State getInitialState() {
    return initialState;
  }

  /** All possible history event types that are known to this state machine instance. */
  public Set<EventType> getValidEventTypes() {
    return validEventTypes;
  }

  /**
   * Registers a transition between states.
   *
   * @param from initial state that transition applies to
   * @param explicitEvent explicitEvent that caused the transition. Delivered through {@link
   *     StateMachine#handleExplicitEvent(Object, Object)}.
   * @param to destination state of a transition.
   * @param action action to invoke upon transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachineDefinition<State, ExplicitEvent, Data> add(
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
   *     StateMachine#handleExplicitEvent(Object, Object)}.
   * @param to destination state of a transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachineDefinition<State, ExplicitEvent, Data> add(
      State from, ExplicitEvent explicitEvent, State to) {
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
   *     StateMachine#handleHistoryEvent(EventType, Object)}.
   * @param to destination state of a transition.
   * @param action action to invoke upon transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachineDefinition<State, ExplicitEvent, Data> add(
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
   *     StateMachine#handleHistoryEvent(EventType, Object)}.
   * @param to destination state of a transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachineDefinition<State, ExplicitEvent, Data> add(
      State from, EventType eventType, State to) {
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
   *     StateMachine#handleHistoryEvent(EventType, Object)}.
   * @param toStates allowed destination states of a transition.
   * @param action action to invoke upon transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachineDefinition<State, ExplicitEvent, Data> add(
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
   *     StateMachine#handleCommand(CommandType, Object)}.
   * @param to destination state of a transition.
   * @param action action to invoke upon transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachineDefinition<State, ExplicitEvent, Data> add(
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
   *     StateMachine#handleCommand(CommandType, Object)}.
   * @param to destination state of a transition.
   * @return the current StateMachine instance for the fluid pattern.
   */
  StateMachineDefinition<State, ExplicitEvent, Data> add(
      State from, CommandType commandType, State to) {
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
  StateMachineDefinition<State, ExplicitEvent, Data> add(
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

  @Override
  public String toString() {
    return "StateMachine{" + "name='" + name + "'}";
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

  public boolean isFinalState(State state) {
    return finalStates.contains(state);
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

  /**
   * Given a list of state machines generate state diagram that shows which state transitions have
   * not been covered.
   */
  public String asPlantUMLStateDiagramCoverage(
      List<StateMachine<State, ExplicitEvent, Data>> stateMachines) {
    Set<State> visited = new HashSet<>();
    Set<Transition> taken = new HashSet<>();
    for (StateMachine<State, ExplicitEvent, Data> stateMachine : stateMachines) {
      List<Transition<State, TransitionEvent<ExplicitEvent>>> history =
          stateMachine.getTransitionHistory();
      for (Transition<State, TransitionEvent<ExplicitEvent>> transition : history) {
        visited.add(transition.getFrom());
        taken.add(transition);
      }
    }
    StringBuilder result = new StringBuilder();
    result.append("@startuml\n" + "scale 350 width\n");
    result.append(
        "skinparam {\n"
            + "  ArrowColor green\n"
            + "  ArrowThickness 2\n"
            + "}\n"
            + "\n"
            + "skinparam state {\n"
            + " BackgroundColor green\n"
            + " BorderColor black\n"
            + " BackgroundColor<<NotCovered>> red\n"
            + "}\n");
    result.append("[*] --> ");
    result.append(initialState);
    result.append('\n');
    for (Map.Entry<Transition<State, TransitionEvent<ExplicitEvent>>, TransitionAction<State, Data>>
        entry : transitions.entrySet()) {
      List<State> targets = entry.getValue().getAllowedStates();
      for (State target : targets) {
        Transition<State, TransitionEvent<ExplicitEvent>> transition = entry.getKey();
        State from = transition.getFrom();
        result.append(from);
        if (!visited.contains(from)) {
          result.append("<< NotCovered >>");
        }
        if (taken.contains(transition)) {
          result.append(" --> ");
        } else {
          result.append(" -[#red]-> ");
        }
        result.append(target);
        result.append(": ");
        result.append(transition.getExplicitEvent());
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

  public TransitionAction<State, Data> getTransitionAction(
      Transition<State, TransitionEvent<ExplicitEvent>> transition) {
    return transitions.get(transition);
  }
}
