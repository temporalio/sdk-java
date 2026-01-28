package io.temporal.internal.statemachines;

import io.temporal.workflow.Functions;
import java.util.Collections;
import java.util.List;

/** Action that can transition to exactly one state. */
class FixedTransitionAction<State, Data> implements TransitionAction<State, Data> {

  final State state;

  final Functions.Proc1<Data> action;

  FixedTransitionAction(State state, Functions.Proc1<Data> action) {
    this.state = state;
    this.action = action;
  }

  @Override
  public String toString() {
    return "FixedTransitionAction{" + "toState=" + state + "}";
  }

  @Override
  public State apply(Data data) {
    action.apply(data);
    return state;
  }

  @Override
  public List<State> getAllowedStates() {
    return Collections.singletonList(state);
  }
}
