package io.temporal.internal.common;

import io.temporal.api.common.v1.Priority;
import io.temporal.api.enums.v1.VersioningBehavior;
import io.temporal.common.VersioningOverride;
import io.temporal.common.WorkerDeploymentVersion;
import javax.annotation.Nonnull;

public class ProtoConverters {
  public static Priority toProto(io.temporal.common.Priority priority) {
    return Priority.newBuilder().setPriorityKey(priority.getPriorityKey()).build();
  }

  @Nonnull
  public static io.temporal.common.Priority fromProto(@Nonnull Priority priority) {
    return io.temporal.common.Priority.newBuilder()
        .setPriorityKey(priority.getPriorityKey())
        .build();
  }

  public static io.temporal.api.deployment.v1.WorkerDeploymentVersion toProto(
      WorkerDeploymentVersion v) {
    return io.temporal.api.deployment.v1.WorkerDeploymentVersion.newBuilder()
        .setBuildId(v.getBuildId())
        .setDeploymentName(v.getDeploymentName())
        .build();
  }

  @SuppressWarnings("deprecation")
  public static io.temporal.api.workflow.v1.VersioningOverride toProto(VersioningOverride v) {
    if (v instanceof VersioningOverride.PinnedVersioningOverride) {
      VersioningOverride.PinnedVersioningOverride pv =
          (VersioningOverride.PinnedVersioningOverride) v;
      io.temporal.api.workflow.v1.VersioningOverride.PinnedOverride.Builder pinnedBuilder =
          io.temporal.api.workflow.v1.VersioningOverride.PinnedOverride.newBuilder()
              .setVersion(toProto(pv.getVersion()));

      pinnedBuilder.setBehavior(
          io.temporal.api.workflow.v1.VersioningOverride.PinnedOverrideBehavior
              .PINNED_OVERRIDE_BEHAVIOR_PINNED);

      return io.temporal.api.workflow.v1.VersioningOverride.newBuilder()
          .setBehavior(VersioningBehavior.VERSIONING_BEHAVIOR_PINNED)
          .setPinnedVersion(pv.getVersion().toCanonicalString())
          .setPinned(pinnedBuilder.build())
          .build();
    } else {
      return io.temporal.api.workflow.v1.VersioningOverride.newBuilder()
          .setBehavior(VersioningBehavior.VERSIONING_BEHAVIOR_AUTO_UPGRADE)
          .setAutoUpgrade(true)
          .build();
    }
  }

  private ProtoConverters() {}
}
