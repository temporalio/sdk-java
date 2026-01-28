package io.temporal.internal.sync;

import io.temporal.workflow.UpdateInfo;

public final class UpdateInfoImpl implements UpdateInfo {
  final String updateName;
  final String updateId;

  UpdateInfoImpl(String updateName, String updateId) {
    this.updateName = updateName;
    this.updateId = updateId;
  }

  @Override
  public String getUpdateName() {
    return updateName;
  }

  @Override
  public String getUpdateId() {
    return updateId;
  }

  @Override
  public String toString() {
    return "UpdateInfoImpl{"
        + "updateName='"
        + updateName
        + '\''
        + ", updateId='"
        + updateId
        + '\''
        + '}';
  }
}
