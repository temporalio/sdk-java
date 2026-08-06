package io.temporal.releaseautomation;

import java.util.ArrayList;
import java.util.List;

public final class CandidateStatus {
  public CandidateIdentity identity;
  public List<String> pendingPlatforms = new ArrayList<>();
  public List<GithubArtifactReceipt> artifacts = new ArrayList<>();
  public ReleaseIdentity releaseIdentity;

  public CandidateStatus() {}

  CandidateStatus(
      CandidateIdentity identity,
      List<String> pendingPlatforms,
      List<GithubArtifactReceipt> artifacts,
      ReleaseIdentity releaseIdentity) {
    this.identity = identity;
    this.pendingPlatforms = new ArrayList<>(pendingPlatforms);
    this.artifacts = new ArrayList<>(artifacts);
    this.releaseIdentity = releaseIdentity;
  }
}
