package io.temporal.common;

import java.util.Objects;

/** Represents the version of a specific worker deployment. */
public class WorkerDeploymentVersion {
  private final String deploymentName;
  private final String buildId;

  /** Build a worker deployment version from an explicit deployment name and build ID. */
  public WorkerDeploymentVersion(String deploymentName, String buildId) {
    this.deploymentName = deploymentName;
    this.buildId = buildId;
  }

  /**
   * @return The canonical string representation of this worker deployment version.
   */
  public String toCanonicalString() {
    return deploymentName + "." + buildId;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    WorkerDeploymentVersion that = (WorkerDeploymentVersion) o;
    return Objects.equals(deploymentName, that.deploymentName)
        && Objects.equals(buildId, that.buildId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(deploymentName, buildId);
  }

  @Override
  public String toString() {
    return "WorkerDeploymentVersion{"
        + "deploymentName='"
        + deploymentName
        + '\''
        + ", buildId='"
        + buildId
        + '\''
        + '}';
  }
}
