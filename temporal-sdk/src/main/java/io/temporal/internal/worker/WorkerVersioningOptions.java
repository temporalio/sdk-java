package io.temporal.internal.worker;

import io.temporal.worker.WorkerDeploymentOptions;
import javax.annotation.Nullable;

/** Contains old and new worker versioning options together. */
public final class WorkerVersioningOptions {
  private final @Nullable String buildId;
  private final boolean useBuildIdForVersioning;
  private final @Nullable WorkerDeploymentOptions workerDeploymentOptions;

  public WorkerVersioningOptions(
      @Nullable String buildId,
      boolean useBuildIdForVersioning,
      @Nullable WorkerDeploymentOptions workerDeploymentOptions) {
    this.buildId = buildId;
    this.useBuildIdForVersioning = useBuildIdForVersioning;
    this.workerDeploymentOptions = workerDeploymentOptions;
  }

  public String getBuildId() {
    if (workerDeploymentOptions != null
        && workerDeploymentOptions.getVersion() != null
        && workerDeploymentOptions.getVersion().getBuildId() != null) {
      return workerDeploymentOptions.getVersion().getBuildId();
    }
    return buildId;
  }

  public boolean isUsingVersioning() {
    return useBuildIdForVersioning
        || (workerDeploymentOptions != null && workerDeploymentOptions.isUsingVersioning());
  }

  @Nullable
  public WorkerDeploymentOptions getWorkerDeploymentOptions() {
    return workerDeploymentOptions;
  }
}
