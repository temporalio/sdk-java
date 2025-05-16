package io.temporal.common;

import io.temporal.api.enums.v1.VersioningBehavior;
import javax.annotation.Nonnull;

/**
 * Represents the override of a worker's versioning behavior for a workflow execution. Exactly one
 * of the subtypes must be used.
 */
@Experimental
public abstract class VersioningOverride {
  private VersioningOverride() {}

  /**
   * Converts this override to a protobuf message.
   *
   * @return The proto representation.
   */
  @SuppressWarnings("deprecation")
  public io.temporal.api.workflow.v1.VersioningOverride toProto() {
    if (this instanceof PinnedVersioningOverride) {
      PinnedVersioningOverride pv = (PinnedVersioningOverride) this;
      io.temporal.api.workflow.v1.VersioningOverride.PinnedOverride.Builder pinnedBuilder =
          io.temporal.api.workflow.v1.VersioningOverride.PinnedOverride.newBuilder()
              .setVersion(pv.getVersion().toProto());

      pinnedBuilder.setBehavior(
          io.temporal.api.workflow.v1.VersioningOverride.PinnedOverrideBehavior
              .PINNED_OVERRIDE_BEHAVIOR_PINNED);

      return io.temporal.api.workflow.v1.VersioningOverride.newBuilder()
          .setBehavior(VersioningBehavior.VERSIONING_BEHAVIOR_PINNED)
          .setPinnedVersion(pv.version.toCanonicalString())
          .setPinned(pinnedBuilder.build())
          .build();
    } else {
      return io.temporal.api.workflow.v1.VersioningOverride.newBuilder()
          .setBehavior(VersioningBehavior.VERSIONING_BEHAVIOR_AUTO_UPGRADE)
          .setAutoUpgrade(true)
          .build();
    }
  }

  /** Workflow will be pinned to a specific deployment version. */
  public static final class PinnedVersioningOverride extends VersioningOverride {
    private final WorkerDeploymentVersion version;

    /**
     * Creates a new PinnedVersioningOverride.
     *
     * @param version The worker deployment version to pin the workflow to.
     */
    public PinnedVersioningOverride(@Nonnull WorkerDeploymentVersion version) {
      this.version = version;
    }

    /**
     * @return The worker deployment version to pin the workflow to.
     */
    public WorkerDeploymentVersion getVersion() {
      return version;
    }
  }

  /** The workflow will auto-upgrade to the current deployment version on the next workflow task. */
  public static final class AutoUpgradeVersioningOverride extends VersioningOverride {
    public AutoUpgradeVersioningOverride() {}
  }
}
