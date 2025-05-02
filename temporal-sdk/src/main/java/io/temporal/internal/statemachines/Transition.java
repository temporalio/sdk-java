package io.temporal.internal.statemachines;

import java.util.Objects;

class Transition<State, Event> {

  final State from;
  final Event event;

  public Transition(State from, Event event) {
    this.from = Objects.requireNonNull(from);
    this.event = Objects.requireNonNull(event);
  }

  public State getFrom() {
    return from;
  }

  public Event getExplicitEvent() {
    return event;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Transition<?, ?> that = (Transition<?, ?>) o;
    return com.google.common.base.Objects.equal(from, that.from)
        && com.google.common.base.Objects.equal(event, that.event);
  }

  @Override
  public int hashCode() {
    return com.google.common.base.Objects.hashCode(from, event);
  }

  @Override
  public String toString() {
    return from + "->" + event;
  }
}
