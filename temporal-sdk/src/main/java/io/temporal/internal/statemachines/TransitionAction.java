package io.temporal.internal.statemachines;

import java.util.List;

/** When an event happens it causes a transition. This class represents the transition action. */
interface TransitionAction<State, Data> {

  /**
   * Executes action returning the new state the state machine should transition to.
   *
   * @param data data that action applies to
   */
  State apply(Data data);

  /** List of states the apply operation can return. */
  List<State> getAllowedStates();
}
