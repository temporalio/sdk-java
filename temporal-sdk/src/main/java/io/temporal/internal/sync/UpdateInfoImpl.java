package io.temporal.internal.sync;

import io.temporal.workflow.UpdateInfo;

final public class UpdateInfoImpl implements UpdateInfo {
    final String updateName;
    final String updateId;

    UpdateInfoImpl(String updateName, String updateId) {
        this.updateName = updateName;
        this.updateId = updateId;
    }

    @Override
    public String getUpdateName() {
        return null;
    }

    @Override
    public String getUpdateId() {
        return null;
    }
}
