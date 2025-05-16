package io.temporal.common;

import javax.annotation.Nonnull;

/** Represents the override of a worker's versioning behavior for a workflow execution. */
@Experimental
public abstract class VersioningOverride {
  private VersioningOverride() {}

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
