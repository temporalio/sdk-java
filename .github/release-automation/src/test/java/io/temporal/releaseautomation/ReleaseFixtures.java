package io.temporal.releaseautomation;

import java.util.ArrayList;
import java.util.List;

final class ReleaseFixtures {
  private ReleaseFixtures() {}

  static CandidateIdentity candidate() {
    return new CandidateIdentity(
        "v1.2.3",
        "0123456789abcdef0123456789abcdef01234567",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "abcdefabcdefabcdefabcdefabcdefabcdefabcd",
        ReleasePolicy.MAVEN_POLICY_CURRENT);
  }

  static ReleaseIdentity release() {
    CandidateIdentity candidate = candidate();
    List<GithubArtifactReceipt> artifacts = new ArrayList<>();
    int index = 1;
    for (String platform : ReleasePolicy.NATIVE_PLATFORMS) {
      String name = ReleasePolicy.nativeArtifactName(candidate.version(), platform);
      artifacts.add(
          artifact(
              ReleasePolicy.githubNativeArtifactName(candidate, platform),
              name,
              index++,
              1000 + index));
    }
    return new ReleaseIdentity(
        candidate, new ArtifactManifest(artifacts), "11111111-2222-3333-4444-555555555555");
  }

  static GithubArtifactReceipt mavenArtifact(ReleaseIdentity release) {
    return artifact(ReleasePolicy.githubMavenArtifactName(release), "maven-payload.tar", 99, 9000);
  }

  static GithubArtifactReceipt artifact(
      String artifactName, String fileName, int index, long size) {
    return new GithubArtifactReceipt(
        1000 + index,
        2000 + index,
        artifactName,
        "sha256:" + String.format("%064x", index + 100),
        "2026-01-01T00:00:00Z",
        "2026-04-01T00:00:00Z",
        java.util.Collections.singletonList(
            new ArtifactEntry(fileName, String.format("%064x", index), size)));
  }
}
