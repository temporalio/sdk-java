/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
