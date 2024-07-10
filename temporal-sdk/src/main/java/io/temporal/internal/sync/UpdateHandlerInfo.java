package io.temporal.internal.sync;

import io.temporal.workflow.HandlerUnfinishedPolicy;

public class UpdateHandlerInfo {
  private String name;
  private HandlerUnfinishedPolicy policy;

  public UpdateHandlerInfo(String name, HandlerUnfinishedPolicy policy) {
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
