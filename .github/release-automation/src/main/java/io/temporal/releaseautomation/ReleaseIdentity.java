package io.temporal.releaseautomation;

import java.util.HashSet;
import java.util.Set;

public final class ReleaseIdentity {
  public CandidateIdentity candidate;
  public ArtifactManifest manifest;
  public String manifestSha256;
  public String candidateRunId;

  public ReleaseIdentity() {}

  public ReleaseIdentity(CandidateIdentity candidate, ArtifactManifest manifest) {
    this(candidate, manifest, "");
  }

  public ReleaseIdentity(
      CandidateIdentity candidate, ArtifactManifest manifest, String candidateRunId) {
    this.candidate = candidate;
    this.manifest = manifest;
    this.manifestSha256 = manifest.digest();
    this.candidateRunId = candidateRunId;
    validate();
  }

  public void validate() {
    if (candidate == null || manifest == null) {
      throw new IllegalArgumentException("Candidate and artifact manifest are required.");
    }
    candidate.validate();
    manifest.validate();
    if (candidateRunId == null
        || (!candidateRunId.isEmpty()
            && !candidateRunId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
      throw new IllegalArgumentException("Candidate Workflow Run ID is invalid.");
    }
    if (!manifest.digest().equals(manifestSha256)) {
      throw new IllegalArgumentException("Artifact manifest hash does not match its contents.");
    }
    Set<String> actual = new HashSet<>();
    Set<String> actualReceipts = new HashSet<>();
    for (GithubArtifactReceipt artifact : manifest.artifacts) {
      if (artifact.files.size() != 1) {
        throw new IllegalArgumentException("Native GitHub artifact identity is invalid.");
      }
      actualReceipts.add(artifact.artifactName);
      actual.add(artifact.files.get(0).name);
    }
    Set<String> expected = new HashSet<>();
    Set<String> expectedReceipts = new HashSet<>();
    for (String platform : ReleasePolicy.NATIVE_PLATFORMS) {
      expected.add(ReleasePolicy.nativeArtifactName(candidate.version(), platform));
      expectedReceipts.add(ReleasePolicy.githubNativeArtifactName(candidate, platform));
    }
    if (!actual.equals(expected) || !actualReceipts.equals(expectedReceipts)) {
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
