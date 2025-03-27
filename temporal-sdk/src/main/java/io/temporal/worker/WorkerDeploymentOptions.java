package io.temporal.worker;

import com.google.common.base.Preconditions;
import io.temporal.common.Experimental;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.WorkerDeploymentVersion;
import java.util.Objects;

@Experimental
public class WorkerDeploymentOptions {
  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private boolean useVersioning;
    private WorkerDeploymentVersion version;
    private VersioningBehavior defaultVersioningBehavior;

    /**
     * If set, opts this worker into the Worker Deployment Versioning feature. It will only operate
     * on workflows it claims to be compatible with. You must also call {@link
     * Builder#setVersion(WorkerDeploymentVersion)}} if this flag is true.
     */
    public Builder setUseVersioning(boolean useVersioning) {
      this.useVersioning = useVersioning;
      return this;
    }

    /** Assign a Deployment Version identifier to this worker. */
    public Builder setVersion(WorkerDeploymentVersion version) {
      this.version = version;
      return this;
    }

    /**
     * Provides a default Versioning Behavior to workflows that do not set one with the TODO: Link
     * to annotation
     *
     * <p>NOTE: When the Deployment-based Worker Versioning feature is on, and default versioning
     * behavior is unspecified, workflows that do not set the Versioning Behavior will fail at
     * registration time.
     */
    public Builder setDefaultVersioningBehavior(VersioningBehavior defaultVersioningBehavior) {
      this.defaultVersioningBehavior = defaultVersioningBehavior;
      return this;
    }

    public WorkerDeploymentOptions build() {
      Preconditions.checkState(
          !(useVersioning && version == null),
          "If useVersioning is set, setVersion must be called");
      return new WorkerDeploymentOptions(useVersioning, version, defaultVersioningBehavior);
    }
  }

  private WorkerDeploymentOptions(
      boolean useVersioning,
      WorkerDeploymentVersion version,
      VersioningBehavior defaultVersioningBehavior) {
    this.useVersioning = useVersioning;
    this.version = version;
    this.defaultVersioningBehavior = defaultVersioningBehavior;
  }

  private final boolean useVersioning;
  private final WorkerDeploymentVersion version;
  private final VersioningBehavior defaultVersioningBehavior;

  public boolean isUsingVersioning() {
    return useVersioning;
  }

  public WorkerDeploymentVersion getVersion() {
    return version;
  }

  public VersioningBehavior getDefaultVersioningBehavior() {
    return defaultVersioningBehavior;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    WorkerDeploymentOptions that = (WorkerDeploymentOptions) o;
    return useVersioning == that.useVersioning
        && Objects.equals(version, that.version)
        && defaultVersioningBehavior == that.defaultVersioningBehavior;
  }

  @Override
  public int hashCode() {
    return Objects.hash(useVersioning, version, defaultVersioningBehavior);
  }

  @Override
  public String toString() {
    return "WorkerDeploymentOptions{"
        + "useVersioning="
        + useVersioning
        + ", version='"
        + version
        + '\''
        + ", defaultVersioningBehavior="
        + defaultVersioningBehavior
        + '}';
  }
}
