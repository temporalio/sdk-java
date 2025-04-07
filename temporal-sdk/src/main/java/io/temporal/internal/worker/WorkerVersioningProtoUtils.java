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
