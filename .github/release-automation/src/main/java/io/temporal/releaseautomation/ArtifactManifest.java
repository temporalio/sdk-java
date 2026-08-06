package io.temporal.releaseautomation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ArtifactManifest {
  public List<GithubArtifactReceipt> artifacts = new ArrayList<>();

  public ArtifactManifest() {}

  public ArtifactManifest(List<GithubArtifactReceipt> artifacts) {
    this.artifacts = new ArrayList<>(artifacts);
    validate();
  }

  public void validate() {
    if (artifacts == null || artifacts.isEmpty()) {
      throw new IllegalArgumentException("The artifact manifest must not be empty.");
    }
    Set<String> names = new HashSet<>();
    for (GithubArtifactReceipt artifact : artifacts) {
      artifact.validate();
      if (!names.add(artifact.artifactName)) {
        throw new IllegalArgumentException("Duplicate artifact name: " + artifact.artifactName);
      }
    }
  }

  public String canonicalForm() {
    validate();
    List<GithubArtifactReceipt> sorted = new ArrayList<>(artifacts);
    Collections.sort(sorted);
    StringBuilder result = new StringBuilder();
    for (GithubArtifactReceipt artifact : sorted) {
      result.append(artifact.canonicalForm()).append('\n');
    }
    return result.toString();
  }

  public String digest() {
    return Digests.sha256(canonicalForm());
  }
}
