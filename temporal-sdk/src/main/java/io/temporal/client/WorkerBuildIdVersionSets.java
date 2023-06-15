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

import io.temporal.api.workflowservice.v1.GetWorkerBuildIdCompatibilityResponse;
import io.temporal.common.Experimental;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Represents the sets of compatible Build Ids associated with a particular task queue. */
@Experimental
public class WorkerBuildIdVersionSets {

  /** Represents a set of Build Ids which are compatible with one another. */
  public static class CompatibleSet {
    private final List<String> buildIds;

    CompatibleSet(List<String> buildIds) {
      this.buildIds = Collections.unmodifiableList(buildIds);
    }

    /**
     * @return All the Build Ids in the set
     */
    public List<String> getBuildIds() {
      return buildIds;
    }

    /**
     * @return The default Build Id for this compatible set.
     */
    public Optional<String> defaultBuildId() {
      if (buildIds.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(buildIds.get(buildIds.size() - 1));
    }
  }

  private final List<CompatibleSet> buildIdVersionSets;

  WorkerBuildIdVersionSets(GetWorkerBuildIdCompatibilityResponse fetchResponse) {
    buildIdVersionSets =
        Collections.unmodifiableList(
            fetchResponse.getMajorVersionSetsList().stream()
                .map(majorSet -> new CompatibleSet(majorSet.getBuildIdsList()))
                .collect(Collectors.toList()));
  }

  /**
   * @return the current overall default Build Id for the queue. Returns empty if there are no
   *     defined Build Ids.
   */
  public Optional<String> defaultBuildId() {
    CompatibleSet defaultSet = defaultSet();
    if (defaultSet == null) {
      return Optional.empty();
    }
    return defaultSet.defaultBuildId();
  }

  /**
   * @return the current overall default compatible set for the queue. Returns null if there are no
   *     defined Build Ids.
   */
  public CompatibleSet defaultSet() {
    if (buildIdVersionSets.isEmpty()) {
      return null;
    }
    return buildIdVersionSets.get(buildIdVersionSets.size() - 1);
  }

  /**
   * @return All compatible sets for the queue. The last set in the list is the overall default.
   */
  public List<CompatibleSet> allSets() {
    return buildIdVersionSets;
  }
}
