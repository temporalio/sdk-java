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
import java.util.List;
import java.util.stream.Collectors;

/** Represents the sets of compatible Build IDs associated with a particular task queue. */
public class WorkerBuildIDVersionSets {

  /** Represents a set of Build IDs which are compatible with one another. */
  public static class CompatibleSet {
    private final List<String> buildIDs;

    CompatibleSet(List<String> buildIDs) {
      this.buildIDs = buildIDs;
    }

    /**
     * @return All the Build IDs in the set
     */
    public List<String> getBuildIDs() {
      return buildIDs;
    }

    /**
     * @return The default Build ID for this compatible set
     */
    public String defaultBuildID() {
      if (buildIDs.isEmpty()) {
        return null;
      }
      return buildIDs.get(buildIDs.size() - 1);
    }
  }

  private final List<CompatibleSet> buildIDVersionSets;

  public WorkerBuildIDVersionSets(GetWorkerBuildIdCompatibilityResponse fetchResponse) {
    buildIDVersionSets =
        fetchResponse.getMajorVersionSetsList().stream()
            .map(majorSet -> new CompatibleSet(majorSet.getBuildIdsList()))
            .collect(Collectors.toList());
  }

  /**
   * @return the current overall default Build ID for the queue. Returns null if there are no
   *     defined Build IDs.
   */
  public String defaultBuildID() {
    CompatibleSet defaultSet = defaultSet();
    if (defaultSet == null) {
      return null;
    }
    return defaultSet.defaultBuildID();
  }

  /**
   * @return the current overall default compatible set for the queue. Returns null if there are no
   *     defined Build IDs.
   */
  public CompatibleSet defaultSet() {
    if (buildIDVersionSets.isEmpty()) {
      return null;
    }
    return buildIDVersionSets.get(buildIDVersionSets.size() - 1);
  }

  /**
   * @return All compatible sets for the queue. The last set in the list is the overall default.
   */
  public List<CompatibleSet> allSets() {
    return buildIDVersionSets;
  }
}
