package io.temporal.internal.statemachines;

/**
 * Function invoked when an explicitEvent happens in a given state. Returns the next state. Used
 * when the next state depends not only on the current state and explicitEvent, but also on the
 * data.
 */
@FunctionalInterface
interface DynamicCallback<State, Data> {

  /**
   * @return state after the explicitEvent
   */
  State apply(Data data);
}
