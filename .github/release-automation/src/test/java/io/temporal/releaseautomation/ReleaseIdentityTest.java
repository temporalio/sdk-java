package io.temporal.releaseautomation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import org.junit.Test;

public class ReleaseIdentityTest {
  @Test
  public void identityAndQueuesAreStableAndReleaseSpecific() {
    ReleaseIdentity release = ReleaseFixtures.release();
    assertEquals(64, release.digest().length());
    assertEquals(
        "sdk-java-release-" + release.digest().substring(0, 32) + "-publication",
        QueueNames.publication(release));
    assertNotEquals(
        QueueNames.candidateWorkflow(release.candidate), QueueNames.releaseWorkflow(release));
    assertTrue(
        release.manifest.artifacts.stream()
            .anyMatch(
                artifact -> artifact.name.equals("temporal-test-server_1.2.3_macOS_amd64.tar.gz")));
  }

  @Test
  public void artifactOrderDoesNotChangeIdentity() {
    ReleaseIdentity release = ReleaseFixtures.release();
    ArrayList<ArtifactEntry> reversed = new ArrayList<>(release.manifest.artifacts);
    Collections.reverse(reversed);
    ReleaseIdentity other =
        new ReleaseIdentity(ReleaseFixtures.candidate(), new ArtifactManifest(reversed));
    assertEquals(release.digest(), other.digest());
  }

  @Test
  public void fixedPlatformSetIsRequired() {
    ReleaseIdentity release = ReleaseFixtures.release();
    release.manifest.artifacts.remove(0);
    release.manifestSha256 = release.manifest.digest();
    assertThrows(IllegalArgumentException.class, release::validate);
  }

  @Test
  public void repositoryIsNotConfigurable() {
    CandidateIdentity candidate = ReleaseFixtures.candidate();
    candidate.repository = "someone/else";
    assertThrows(IllegalArgumentException.class, candidate::validate);
  }

  @Test
  public void exactMavenPublicationSetIsHardcoded() {
    assertEquals(17, ReleasePolicy.MAVEN_ARTIFACTS.size());
    assertEquals("io.temporal", ReleasePolicy.MAVEN_GROUP);
    assertEquals("https://repo1.maven.org/maven2", ReleasePolicy.MAVEN_CENTRAL_BASE);
    assertTrue(ReleasePolicy.MAVEN_ARTIFACTS.contains("temporal-sdk"));
    assertTrue(ReleasePolicy.MAVEN_ARTIFACTS.contains("temporal-workflowstreams"));
  }
}
