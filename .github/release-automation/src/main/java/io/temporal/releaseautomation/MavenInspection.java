package io.temporal.releaseautomation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MavenInspection {
  public int centralPresent;
  public int centralMissing;
  public List<MavenGenerationInspection> generations = new ArrayList<>();

  public MavenInspection() {}

  public void validate(String releaseDigest) {
    if (centralPresent < 0
        || centralMissing < 0
        || centralPresent + centralMissing <= 0
        || centralPresent + centralMissing > ReleasePolicy.MAVEN_ARTIFACTS.size()) {
      throw new IllegalArgumentException("Inspected Maven Central state is invalid.");
    }
    if (generations == null) {
      throw new IllegalArgumentException("Inspected Maven generations are missing.");
    }
    Set<Integer> found = new HashSet<>();
    for (MavenGenerationInspection generation : generations) {
      generation.validate(releaseDigest);
      if (!found.add(generation.generation)) {
        throw new IllegalArgumentException("Inspected Maven generation is duplicated.");
      }
    }
  }

  public String canonicalForm(String releaseDigest) {
    validate(releaseDigest);
    List<MavenGenerationInspection> sorted = new ArrayList<>(generations);
    Collections.sort(sorted);
    StringBuilder result =
        new StringBuilder().append(centralPresent).append('\n').append(centralMissing).append('\n');
    for (MavenGenerationInspection generation : sorted) {
      result.append(generation.canonicalForm()).append('\n');
    }
    return result.toString();
  }
}
