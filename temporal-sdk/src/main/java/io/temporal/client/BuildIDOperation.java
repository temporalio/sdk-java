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

package io.temporal.client;

import io.temporal.api.workflowservice.v1.UpdateWorkerBuildIdCompatibilityRequest;
import javax.annotation.Nonnull;

/**
 * The implementations of this class can be passed as parameters to {@link
 * WorkflowClient#updateWorkerBuildIDCompatability(String, BuildIDOperation)}
 *
 * <p>See each public static method to learn about and construct the available operations.
 */
public abstract class BuildIDOperation {
  private BuildIDOperation() {}

  abstract void augmentBuilder(UpdateWorkerBuildIdCompatibilityRequest.Builder builder);

  /**
   * This operation adds a new Build ID into a new set, which will be used as the default set for
   * the queue. This means all new workflows will start on this Build ID.
   *
   * @param buildID The Build ID to add as the new overall default.
   */
  public static BuildIDOperation newIDInNewDefaultSet(@Nonnull String buildID) {
    return new BuildIDOperation() {
      @Override
      void augmentBuilder(UpdateWorkerBuildIdCompatibilityRequest.Builder builder) {
        builder.setAddNewBuildIdInNewDefaultSet(buildID);
      }
    };
  }

  /**
   * This operation adds a new Build ID into an existing compatible set. The newly added ID becomes
   * the default for that compatible set, and thus new workflow tasks for workflows which have been
   * executing on workers in that set will now start on this new Build ID.
   *
   * @param buildID The Build ID to add to an existing compatible set.
   * @param existingCompatibleBuildID A Build ID which must already be defined on the task queue,
   *     and is used to find the compatible set to add the new ID to.
   * @param makeSetDefault If set to true, the targeted set will also be promoted to become the
   *     overall default set for the queue.
   */
  public static BuildIDOperation newCompatibleVersion(
      @Nonnull String buildID, @Nonnull String existingCompatibleBuildID, boolean makeSetDefault) {
    return new BuildIDOperation() {
      @Override
      void augmentBuilder(UpdateWorkerBuildIdCompatibilityRequest.Builder builder) {
        builder.setAddNewCompatibleBuildId(
            UpdateWorkerBuildIdCompatibilityRequest.AddNewCompatibleVersion.newBuilder()
                .setNewBuildId(buildID)
                .setExistingCompatibleBuildId(existingCompatibleBuildID)
                .setMakeSetDefault(makeSetDefault)
                .build());
      }
    };
  }

  /**
   * Performs {@link #newCompatibleVersion(String, String, boolean)}, with `makeSetDefault` set to
   * false.
   */
  public static BuildIDOperation newCompatibleVersion(
      @Nonnull String buildID, @Nonnull String existingCompatibleBuildID) {
    return newCompatibleVersion(buildID, existingCompatibleBuildID, false);
  }

  /**
   * This operation promotes a set to become the overall default set for the queue.
   *
   * @param buildID An existing Build ID which is used to find the set to be promoted.
   */
  public static BuildIDOperation promoteSetByBuildID(@Nonnull String buildID) {
    return new BuildIDOperation() {
      @Override
      void augmentBuilder(UpdateWorkerBuildIdCompatibilityRequest.Builder builder) {
        builder.setPromoteSetByBuildId(buildID);
      }
    };
  }

  /**
   * This operation promotes a Build ID inside some compatible set to become the default ID in that
   * set.
   *
   * @param buildID An existing Build ID which will be promoted within its compatible set.
   */
  public static BuildIDOperation promoteBuildIDWithinSet(@Nonnull String buildID) {
    return new BuildIDOperation() {
      @Override
      void augmentBuilder(UpdateWorkerBuildIdCompatibilityRequest.Builder builder) {
        builder.setPromoteBuildIdWithinSet(buildID);
      }
    };
  }

  /**
   * This operation merges two sets into one set, thus declaring all the Build IDs in both as
   * compatible with one another. The default of the primary set is maintained as the merged set's
   * overall default.
   *
   * @param primaryBuildID A build ID which is used to find the primary set to be merged.
   * @param secondaryBuildID A build ID which is used to find the secondary set to be merged.
   */
  public static BuildIDOperation mergeSets(
      @Nonnull String primaryBuildID, @Nonnull String secondaryBuildID) {
    return new BuildIDOperation() {
      @Override
      void augmentBuilder(UpdateWorkerBuildIdCompatibilityRequest.Builder builder) {
        builder.setMergeSets(
            UpdateWorkerBuildIdCompatibilityRequest.MergeSets.newBuilder()
                .setPrimarySetBuildId(primaryBuildID)
                .setSecondarySetBuildId(secondaryBuildID)
                .build());
      }
    };
  }
}
