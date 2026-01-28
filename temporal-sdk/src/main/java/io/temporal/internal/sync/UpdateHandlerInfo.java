package io.temporal.internal.sync;

import io.temporal.workflow.HandlerUnfinishedPolicy;

public class UpdateHandlerInfo {
  private String updateId;
  private String name;
  private HandlerUnfinishedPolicy policy;

  public UpdateHandlerInfo(String updateId, String name, HandlerUnfinishedPolicy policy) {
    this.updateId = updateId;
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
    return "UpdateHandlerInfo{"
        + "updateId='"
        + updateId
        + '\''
        + ", name='"
        + name
        + '\''
        + ", policy="
        + policy
        + '}';
  }
}
