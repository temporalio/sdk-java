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
 * WorkflowClient#updateWorkerBuildIdCompatability(String, BuildIdOperation)}
 *
 * <p>See each public static method to learn about and construct the available operations.
 */
public abstract class BuildIdOperation {
  private BuildIdOperation() {}

  abstract void augmentBuilder(UpdateWorkerBuildIdCompatibilityRequest.Builder builder);

  /**
   * This operation adds a new Build Id into a new set, which will be used as the default set for
   * the queue. This means all new workflows will start on this Build Id.
   *
   * @param buildId The Build Id to add as the new overall default.
   */
  public static BuildIdOperation newIdInNewDefaultSet(@Nonnull String buildId) {
    return new NewIdInNewDefaultSet(buildId);
  }

  /**
   * This operation adds a new Build Id into an existing compatible set. The newly added ID becomes
   * the default for that compatible set, and thus new workflow tasks for workflows which have been
   * executing on workers in that set will now start on this new Build Id.
   *
   * @param buildId The Build Id to add to an existing compatible set.
   * @param existingCompatibleBuildId A Build Id which must already be defined on the task queue,
   *     and is used to find the compatible set to add the new ID to.
   * @param makeSetDefault If set to true, the targeted set will also be promoted to become the
   *     overall default set for the queue.
   */
  public static BuildIdOperation newCompatibleVersion(
      @Nonnull String buildId, @Nonnull String existingCompatibleBuildId, boolean makeSetDefault) {
    return new NewCompatibleVersion(buildId, existingCompatibleBuildId, makeSetDefault);
  }

  /**
   * Performs {@link #newCompatibleVersion(String, String, boolean)}, with `makeSetDefault` set to
   * false.
   */
  public static BuildIdOperation newCompatibleVersion(
      @Nonnull String buildId, @Nonnull String existingCompatibleBuildId) {
    return newCompatibleVersion(buildId, existingCompatibleBuildId, false);
  }

  /**
   * This operation promotes a set to become the overall default set for the queue.
   *
   * @param buildId An existing Build Id which is used to find the set to be promoted.
   */
  public static BuildIdOperation promoteSetByBuildId(@Nonnull String buildId) {
    return new PromoteSetByBuildId(buildId);
  }

  /**
   * This operation promotes a Build Id inside some compatible set to become the default ID in that
   * set.
   *
   * @param buildId An existing Build Id which will be promoted within its compatible set.
   */
  public static BuildIdOperation promoteBuildIdWithinSet(@Nonnull String buildId) {
    return new PromoteBuildIdWithinSet(buildId);
  }

  /**
   * This operation merges two sets into one set, thus declaring all the Build Ids in both as
   * compatible with one another. The default of the primary set is maintained as the merged set's
   * overall default.
   *
   * @param primaryBuildId A Build Id which is used to find the primary set to be merged.
   * @param secondaryBuildId A Build Id which is used to find the secondary set to be merged.
   */
  public static BuildIdOperation mergeSets(
      @Nonnull String primaryBuildId, @Nonnull String secondaryBuildId) {
    return new MergeSets(primaryBuildId, secondaryBuildId);
  }

  private static class NewIdInNewDefaultSet extends BuildIdOperation {
    private final String buildId;

    public NewIdInNewDefaultSet(String buildId) {
      this.buildId = buildId;
    }

    @Override
    void augmentBuilder(UpdateWorkerBuildIdCompatibilityRequest.Builder builder) {
      builder.setAddNewBuildIdInNewDefaultSet(buildId);
    }
  }

  private static class NewCompatibleVersion extends BuildIdOperation {
    private final String buildId;
    private final String existingCompatibleBuildId;
    private final boolean makeSetDefault;

    public NewCompatibleVersion(
        String buildId, String existingCompatibleBuildId, boolean makeSetDefault) {
      this.buildId = buildId;
      this.existingCompatibleBuildId = existingCompatibleBuildId;
      this.makeSetDefault = makeSetDefault;
    }

    @Override
    void augmentBuilder(UpdateWorkerBuildIdCompatibilityRequest.Builder builder) {
      builder.setAddNewCompatibleBuildId(
          UpdateWorkerBuildIdCompatibilityRequest.AddNewCompatibleVersion.newBuilder()
              .setNewBuildId(buildId)
              .setExistingCompatibleBuildId(existingCompatibleBuildId)
              .setMakeSetDefault(makeSetDefault)
              .build());
    }
  }

  private static class PromoteSetByBuildId extends BuildIdOperation {
    private final String buildId;

    public PromoteSetByBuildId(String buildId) {
      this.buildId = buildId;
    }

    @Override
    void augmentBuilder(UpdateWorkerBuildIdCompatibilityRequest.Builder builder) {
      builder.setPromoteSetByBuildId(buildId);
    }
  }

  private static class PromoteBuildIdWithinSet extends BuildIdOperation {
    private final String buildId;

    public PromoteBuildIdWithinSet(String buildId) {
      this.buildId = buildId;
    }

    @Override
    void augmentBuilder(UpdateWorkerBuildIdCompatibilityRequest.Builder builder) {
      builder.setPromoteBuildIdWithinSet(buildId);
    }
  }

  private static class MergeSets extends BuildIdOperation {
    private final String primaryBuildId;
    private final String secondaryBuildId;

    public MergeSets(String primaryBuildId, String secondaryBuildId) {
      this.primaryBuildId = primaryBuildId;
      this.secondaryBuildId = secondaryBuildId;
    }

    @Override
    void augmentBuilder(UpdateWorkerBuildIdCompatibilityRequest.Builder builder) {
      builder.setMergeSets(
          UpdateWorkerBuildIdCompatibilityRequest.MergeSets.newBuilder()
              .setPrimarySetBuildId(primaryBuildId)
              .setSecondarySetBuildId(secondaryBuildId)
              .build());
    }
  }
}
