package io.temporal.internal.worker;

import io.temporal.api.enums.v1.WorkerVersioningMode;
import io.temporal.common.VersioningBehavior;
import io.temporal.worker.WorkerDeploymentOptions;
import javax.annotation.Nonnull;

public class WorkerVersioningProtoUtils {
  public static io.temporal.api.deployment.v1.WorkerDeploymentOptions deploymentOptionsToProto(
      @Nonnull WorkerDeploymentOptions options) {
    return io.temporal.api.deployment.v1.WorkerDeploymentOptions.newBuilder()
        .setBuildId(options.getVersion().getBuildId())
        .setDeploymentName(options.getVersion().getDeploymentName())
        .setWorkerVersioningMode(
            options.isUsingVersioning()
                ? WorkerVersioningMode.WORKER_VERSIONING_MODE_VERSIONED
                : WorkerVersioningMode.WORKER_VERSIONING_MODE_UNVERSIONED)
        .build();
  }

  public static io.temporal.api.enums.v1.VersioningBehavior behaviorToProto(
      @Nonnull VersioningBehavior behavior) {
    switch (behavior) {
      case AUTO_UPGRADE:
        return io.temporal.api.enums.v1.VersioningBehavior.VERSIONING_BEHAVIOR_AUTO_UPGRADE;
      case PINNED:
        return io.temporal.api.enums.v1.VersioningBehavior.VERSIONING_BEHAVIOR_PINNED;
      default:
        return io.temporal.api.enums.v1.VersioningBehavior.VERSIONING_BEHAVIOR_UNSPECIFIED;
    }
  }
}
