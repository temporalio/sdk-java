/*
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

package com.uber.cadence.internal.testservice;

import com.uber.cadence.BadRequestError;
import com.uber.cadence.InternalServiceError;
import com.uber.cadence.internal.testservice.StateMachines.Action;
import com.uber.cadence.internal.testservice.StateMachines.State;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * State machine of a single server side entity like activity, decision or the whole workflow.
 *
 * <p>Based on the idea that each entity goes through state transitions and the same operation like
 * timeout is applicable to some states only and can lead to different actions in each state. Each
 * valid state transition should be registered through {@link #add(State, Action, State, Callback)}.
 * The associated callback is invoked when the state transition is requested.
 *
 * @see StateMachines for entity factories.
 */
final class StateMachine<Data> {

  /** Function invoked when an action happens in a given state */
  @FunctionalInterface
  interface Callback<D, R> {

    void apply(RequestContext ctx, D data, R request, long referenceId)
        throws InternalServiceError, BadRequestError;
  }

  /**
   * Function invoked when an action happens in a given state. Returns the next state. Used when the
   * next state depends not only on the current state and action, but also on the data.
   */
  @FunctionalInterface
  interface DynamicCallback<D, R> {

    /** @return state after the action */
    State apply(RequestContext ctx, D data, R request, long referenceId)
        throws InternalServiceError, BadRequestError;
  }

  private static class Transition {

    final State from;
    final Action action;

    public Transition(State from, Action action) {
      this.from = Objects.requireNonNull(from);
      this.action = Objects.requireNonNull(action);
    }

    public State getFrom() {
      return from;
    }

    public Action getAction() {
      return action;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || !(o instanceof Transition)) {
        return false;
      }

      Transition that = (Transition) o;

      if (from != that.from) {
        return false;
      }
      return action == that.action;
    }

    @Override
    public int hashCode() {
      int result = from.hashCode();
      result = 31 * result + action.hashCode();
      return result;
    }

    @Override
    public String toString() {
      return "Transition{" + "from=" + from + ", action=" + action + '}';
    }
  }

  private interface TransitionDestination<Data, R> {
    State apply(RequestContext ctx, Data data, R request, long referenceId)
        throws InternalServiceError, BadRequestError;
  }

  private static class FixedTransitionDestination<Data, R>
      implements TransitionDestination<Data, R> {

    final State state;

    final Callback<Data, R> callback;

    private FixedTransitionDestination(State state, Callback<Data, R> callback) {
      this.state = state;
      this.callback = callback;
    }

    @Override
    public String toString() {
      return "TransitionDestination{" + "state=" + state + ", callback=" + callback + '}';
    }

    @Override
    public State apply(RequestContext ctx, Data data, R request, long referenceId)
        throws InternalServiceError, BadRequestError {
      callback.apply(ctx, data, request, referenceId);
      return state;
    }
  }

  private static class DynamicTransitionDestination<Data, R>
      implements TransitionDestination<Data, R> {

    final DynamicCallback<Data, R> callback;
    State[] expectedStates;
    State state;

    private DynamicTransitionDestination(
        State[] expectedStates, DynamicCallback<Data, R> callback) {
      this.expectedStates = expectedStates;
      this.callback = callback;
    }

    @Override
    public String toString() {
      return "DynamicTransitionDestination{" + "state=" + state + ", callback=" + callback + '}';
    }

    @Override
    public State apply(RequestContext ctx, Data data, R request, long referenceId)
        throws InternalServiceError, BadRequestError {
      state = callback.apply(ctx, data, request, referenceId);
      for (State s : expectedStates) {
        if (s == state) {
          return state;
        }
      }
      throw new IllegalStateException(
          state + " is not expected. Expected states are: " + Arrays.toString(expectedStates));
    }
  }

  private final List<Transition> transitionHistory = new ArrayList<>();
  private final Map<Transition, TransitionDestination<Data, ?>> transitions = new HashMap<>();

  private State state = StateMachines.State.NONE;

  private final Data data;

  StateMachine(Data data) {
    this.data = Objects.requireNonNull(data);
  }

  public State getState() {
    return state;
  }

  public Data getData() {
    return data;
  }

  /**
   * Registers a transition between states.
   *
   * @param from initial state that transition applies to
   * @param to destination state of a transition.
   * @param callback callback to invoke upon transition
   * @param <V> type of callback parameter.
   * @return the current StateMachine instance for the fluid pattern.
   */
  <V> StateMachine<Data> add(State from, Action action, State to, Callback<Data, V> callback) {
    transitions.put(new Transition(from, action), new FixedTransitionDestination<>(to, callback));
    return this;
  }

  /**
   * Registers a dynamic transition between states. Used when the same action can transition to more
   * than one state depending on data.
   *
   * @param from initial state that transition applies to
   * @param toStates allowed destination states of a transition.
   * @param callback callback to invoke upon transition
   * @param <V> type of callback parameter.
   * @return the current StateMachine instance for the fluid pattern.
   */
  <V> StateMachine<Data> add(
      State from, Action action, State[] toStates, DynamicCallback<Data, V> callback) {
    transitions.put(
        new Transition(from, action), new DynamicTransitionDestination<>(toStates, callback));
    return this;
  }

  <R> void action(Action action, RequestContext context, R request, long referenceId)
      throws InternalServiceError, BadRequestError {
    Transition transition = new Transition(state, action);
    @SuppressWarnings("unchecked")
    TransitionDestination<Data, R> destination =
        (TransitionDestination<Data, R>) transitions.get(transition);
    if (destination == null) {
      throw new InternalServiceError("Invalid " + transition + ", history: " + transitionHistory);
    }
    state = destination.apply(context, data, request, referenceId);
    transitionHistory.add(transition);
  }
}
