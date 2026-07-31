package io.temporal.releaseautomation;

import java.util.HashSet;
import java.util.Set;

public final class ReleaseIdentity {
  public CandidateIdentity candidate;
  public ArtifactManifest manifest;
  public String manifestSha256;

  public ReleaseIdentity() {}

  public ReleaseIdentity(CandidateIdentity candidate, ArtifactManifest manifest) {
    this.candidate = candidate;
    this.manifest = manifest;
    this.manifestSha256 = manifest.digest();
    validate();
  }

  public void validate() {
    if (candidate == null || manifest == null) {
      throw new IllegalArgumentException("Candidate and artifact manifest are required.");
    }
    candidate.validate();
    manifest.validate();
    if (!manifest.digest().equals(manifestSha256)) {
      throw new IllegalArgumentException("Artifact manifest hash does not match its contents.");
    }
    Set<String> actual = new HashSet<>();
    for (ArtifactEntry artifact : manifest.artifacts) {
      actual.add(artifact.name);
      String prefix = "sdk-java/" + candidate.digest() + "/";
      if (!artifact.storageKey.equals(prefix + artifact.name)) {
        throw new IllegalArgumentException("Artifact storage key is not candidate-specific.");
      }
    }
    Set<String> expected = new HashSet<>();
    for (String platform : ReleasePolicy.NATIVE_PLATFORMS) {
      expected.add(ReleasePolicy.nativeArtifactName(candidate.version, platform));
    }
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          "Artifact manifest is not the fixed sdk-java platform set.");
    }
  }

  public String canonicalForm() {
    validate();
    return candidate.canonicalForm() + "\n" + manifestSha256 + "\n" + manifest.canonicalForm();
  }

  public String digest() {
    return Digests.sha256(canonicalForm());
  }
}
