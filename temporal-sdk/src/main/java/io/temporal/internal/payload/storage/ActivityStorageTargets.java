package io.temporal.internal.payload.storage;

import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import javax.annotation.Nullable;

/** Chooses the storage target an activity's payloads belong to. */
public final class ActivityStorageTargets {

  public static Builder newBuilder(String namespace) {
    return new Builder(namespace);
  }

  private ActivityStorageTargets() {}

  public static final class Builder {
    private final String namespace;
    private @Nullable String activityId;
    private @Nullable String activityRunId;
    private @Nullable String activityType;
    private @Nullable String workflowId;
    private @Nullable String workflowRunId;
    private @Nullable String workflowType;

    private Builder(String namespace) {
      this.namespace = namespace;
    }

    public Builder setActivity(
        @Nullable String activityId,
        @Nullable String activityRunId,
        @Nullable String activityType) {
      this.activityId = activityId;
      this.activityRunId = activityRunId;
      this.activityType = activityType;
      return this;
    }

    public Builder setWorkflow(
        @Nullable String workflowId,
        @Nullable String workflowRunId,
        @Nullable String workflowType) {
      this.workflowId = workflowId;
      this.workflowRunId = workflowRunId;
      this.workflowType = workflowType;
      return this;
    }

    /**
     * An activity scheduled by a workflow targets that workflow; a standalone activity targets
     * itself. A workflow id is present only in the former case.
     */
    public StorageDriverTargetInfo build() {
      if (workflowId != null) {
        return new StorageDriverWorkflowInfo(namespace, workflowId, workflowRunId, workflowType);
      }
      return new StorageDriverActivityInfo(namespace, activityId, activityRunId, activityType);
    }
  }
}
