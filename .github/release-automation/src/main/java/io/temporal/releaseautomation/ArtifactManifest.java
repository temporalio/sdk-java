package io.temporal.releaseautomation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ArtifactManifest {
  public String storagePrefix;
  public List<ArtifactEntry> artifacts = new ArrayList<>();

  public ArtifactManifest() {}

  public ArtifactManifest(String storagePrefix, List<ArtifactEntry> artifacts) {
    this.storagePrefix = storagePrefix;
    this.artifacts = new ArrayList<>(artifacts);
    validate();
  }

  public void validate() {
    if (storagePrefix == null || !storagePrefix.matches("sdk-java/[0-9a-f]{64}/")) {
      throw new IllegalArgumentException("Artifact storage prefix is invalid.");
    }
    if (artifacts == null || artifacts.isEmpty()) {
      throw new IllegalArgumentException("The artifact manifest must not be empty.");
    }
    Set<String> names = new HashSet<>();
    for (ArtifactEntry artifact : artifacts) {
      artifact.validate();
      if (!names.add(artifact.name)) {
        throw new IllegalArgumentException("Duplicate artifact name: " + artifact.name);
      }
    }
  }

  public String canonicalForm() {
    validate();
    List<ArtifactEntry> sorted = new ArrayList<>(artifacts);
    Collections.sort(sorted);
    StringBuilder result = new StringBuilder(storagePrefix).append('\n');
    for (ArtifactEntry artifact : sorted) {
      result.append(artifact.canonicalForm()).append('\n');
    }
    return result.toString();
  }

  public String digest() {
    return Digests.sha256(canonicalForm());
  }
}
