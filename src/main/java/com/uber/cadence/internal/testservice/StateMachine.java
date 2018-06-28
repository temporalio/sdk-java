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

  @FunctionalInterface
  interface Callback<D, R> {

    void apply(RequestContext ctx, D data, R request, long referenceId)
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
      if (o == null || getClass() != o.getClass()) {
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

  private static class TransitionDestination<Data> {

    final State state;

    final Callback<Data, ?> callback;

    private TransitionDestination(State state, Callback<Data, ?> callback) {
      this.state = state;
      this.callback = callback;
    }

    public State getState() {
      return state;
    }

    public Callback<Data, ?> getCallback() {
      return callback;
    }

    @Override
    public String toString() {
      return "TransitionDestination{" + "state=" + state + ", callback=" + callback + '}';
    }
  }

  private final List<Transition> transitionHistory = new ArrayList<>();
  private final Map<Transition, TransitionDestination<Data>> transitions = new HashMap<>();

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
   * @return the current StateMachine instance for fluid pattern.
   */
  <V> StateMachine<Data> add(State from, Action action, State to, Callback<Data, V> callback) {
    transitions.put(new Transition(from, action), new TransitionDestination<>(to, callback));
    return this;
  }

  <V> void action(Action action, RequestContext context, V request, long referenceId)
      throws InternalServiceError, BadRequestError {
    Transition transition = new Transition(state, action);
    TransitionDestination<Data> destination = transitions.get(transition);
    if (destination == null) {
      throw new InternalServiceError("Invalid " + transition + ", history: " + transitionHistory);
    }
    @SuppressWarnings("unchecked")
    Callback<Data, V> callback = (Callback<Data, V>) destination.getCallback();
    callback.apply(context, data, request, referenceId);
    transitionHistory.add(transition);
    state = destination.getState();
  }
}
