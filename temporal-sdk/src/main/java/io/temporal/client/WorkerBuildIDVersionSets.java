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
