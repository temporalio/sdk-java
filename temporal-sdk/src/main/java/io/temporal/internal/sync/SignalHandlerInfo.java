package io.temporal.internal.sync;

import io.temporal.workflow.HandlerUnfinishedPolicy;

public class SignalHandlerInfo {
  private final String name;
  private final HandlerUnfinishedPolicy policy;

  public SignalHandlerInfo(String name, HandlerUnfinishedPolicy policy) {
    this.name = name;
    this.policy = policy;
  }

  public String getName() {
    return name;
  }

  public HandlerUnfinishedPolicy getPolicy() {
    return policy;
  }
}
