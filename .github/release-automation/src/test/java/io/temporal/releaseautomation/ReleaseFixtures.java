package io.temporal.releaseautomation;

import java.util.ArrayList;
import java.util.List;

final class ReleaseFixtures {
  private ReleaseFixtures() {}

  static CandidateIdentity candidate() {
    return new CandidateIdentity(
        CandidateIdentity.REPOSITORY,
        "1.2.3",
        "v1.2.3",
        "0123456789abcdef0123456789abcdef01234567",
        "releases/v1.2.3",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "abcdefabcdefabcdefabcdefabcdefabcdefabcd",
        ReleasePolicy.MAVEN_POLICY_CURRENT);
  }

  static ReleaseIdentity release() {
    CandidateIdentity candidate = candidate();
    List<ArtifactEntry> artifacts = new ArrayList<>();
    int index = 1;
    for (String platform : ReleasePolicy.NATIVE_PLATFORMS) {
      String name = ReleasePolicy.nativeArtifactName(candidate.version, platform);
      artifacts.add(
          new ArtifactEntry(
              name,
              String.format("%064x", index++),
              1000 + index,
              "sdk-java/" + candidate.digest() + "/" + name));
    }
    return new ReleaseIdentity(candidate, new ArtifactManifest(artifacts));
  }
}
