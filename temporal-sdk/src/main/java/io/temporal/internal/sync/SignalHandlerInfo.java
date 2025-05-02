package io.temporal.internal.sync;

import io.temporal.workflow.HandlerUnfinishedPolicy;

public class SignalHandlerInfo {
  private final long eventId;
  private final String name;
  private final HandlerUnfinishedPolicy policy;

  public SignalHandlerInfo(long eventId, String name, HandlerUnfinishedPolicy policy) {
    this.eventId = eventId;
    this.name = name;
    this.policy = policy;
  }

  public String getName() {
    return name;
  }

  public HandlerUnfinishedPolicy getPolicy() {
    return policy;
  }

  @Override
  public String toString() {
    return "SignalHandlerInfo{"
        + "eventId="
        + eventId
        + ", name='"
        + name
        + '\''
        + ", policy="
        + policy
        + '}';
  }
}
