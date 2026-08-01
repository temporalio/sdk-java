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
    String emergencyPrefix = null;
    boolean normalArtifacts = false;
    for (ArtifactEntry artifact : manifest.artifacts) {
      actual.add(artifact.name);
      String prefix = "sdk-java/" + candidate.digest() + "/";
      String emergencyRoot = "sdk-java/emergency-artifacts/" + candidate.digest() + "/";
      boolean normal = artifact.storageKey.equals(prefix + artifact.name);
      boolean emergency =
          artifact.storageKey.matches(
              java.util.regex.Pattern.quote(emergencyRoot)
                  + "[0-9a-f]{64}/"
                  + java.util.regex.Pattern.quote(artifact.name));
      if (!normal && !emergency) {
        throw new IllegalArgumentException("Artifact storage key is not candidate-specific.");
      }
      if (emergency) {
        if (normalArtifacts) {
          throw new IllegalArgumentException("Normal and emergency artifacts cannot be mixed.");
        }
        String artifactPrefix =
            artifact.storageKey.substring(0, artifact.storageKey.length() - artifact.name.length());
        if (emergencyPrefix == null) {
          emergencyPrefix = artifactPrefix;
        } else if (!emergencyPrefix.equals(artifactPrefix)) {
          throw new IllegalArgumentException("Emergency artifacts mix replacement manifests.");
        }
      } else if (emergencyPrefix != null) {
        throw new IllegalArgumentException("Normal and emergency artifacts cannot be mixed.");
      } else {
        normalArtifacts = true;
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
