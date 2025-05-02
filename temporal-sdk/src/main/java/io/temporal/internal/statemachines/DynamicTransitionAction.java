package io.temporal.internal.statemachines;

import java.util.Arrays;
import java.util.List;

/** Action that can dynamically decide to which state to transition. */
class DynamicTransitionAction<State, Data> implements TransitionAction<State, Data> {

  final DynamicCallback<State, Data> callback;
  State[] expectedStates;

  DynamicTransitionAction(State[] expectedStates, DynamicCallback<State, Data> callback) {
    this.expectedStates = expectedStates;
    this.callback = callback;
  }

  @Override
  public State apply(Data data) {
    State state = callback.apply(data);
    for (State s : expectedStates) {
      if (s.equals(state)) {
        return state;
      }
    }
    throw new IllegalStateException(
        state + " is not expected. Expected states are: " + Arrays.toString(expectedStates));
  }

  @Override
  public List<State> getAllowedStates() {
    return Arrays.asList(expectedStates);
  }

  @Override
  public String toString() {
    return "DynamicTransitionAction{" + "expectedStates=" + Arrays.toString(expectedStates) + '}';
  }
}
