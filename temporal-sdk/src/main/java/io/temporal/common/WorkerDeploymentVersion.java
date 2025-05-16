package io.temporal.common;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Represents the version of a specific worker deployment. */
@Experimental
public class WorkerDeploymentVersion {
  private final String deploymentName;
  private final String buildId;

  /** Build a worker deployment version from an explicit deployment name and build ID. */
  public WorkerDeploymentVersion(@Nonnull String deploymentName, @Nonnull String buildId) {
    this.deploymentName = deploymentName;
    this.buildId = buildId;
  }

  /**
   * Build a worker deployment version from a canonical string representation.
   *
   * @param canonicalString The canonical string representation of the worker deployment version,
   *     formatted as "deploymentName.buildId". Deployment name must not have a "." in it.
   * @return A new instance of {@link WorkerDeploymentVersion}.
   * @throws IllegalArgumentException if the input string is not in the expected format.
   */
  public static WorkerDeploymentVersion fromCanonicalString(String canonicalString) {
    String[] parts = canonicalString.split("\\.", 2);
    if (parts.length != 2) {
      throw new IllegalArgumentException(
          "Invalid canonical string format. Expected 'deploymentName.buildId'");
    }
    return new WorkerDeploymentVersion(parts[0], parts[1]);
  }

  /**
   * @return The canonical string representation of this worker deployment version.
   */
  public String toCanonicalString() {
    return deploymentName + "." + buildId;
  }

  /**
   * @return The name of the deployment.
   */
  @Nullable // Marked nullable for future compatibility with custom strings
  public String getDeploymentName() {
    return deploymentName;
  }

  /**
   * @return The Build ID of this version.
   */
  @Nullable // Marked nullable for future compatibility with custom strings
  public String getBuildId() {
    return buildId;
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
