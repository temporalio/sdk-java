package io.temporal.releaseautomation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ReleaseIdentityTest {
  @Test
  public void identityAndQueuesAreStableAndReleaseSpecific() {
    ReleaseIdentity release = ReleaseFixtures.release();
    assertEquals(64, release.digest().length());
    assertEquals(
        "sdk-java-release-" + release.digest().substring(0, 32) + "-publication-g0",
        QueueNames.publication(release));
    assertNotEquals(QueueNames.publication(release, 0), QueueNames.publication(release, 1));
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
  public void candidateRunReceiptDoesNotChangeImmutableReleaseDigest() {
    ReleaseIdentity release = ReleaseFixtures.release();
    ReleaseIdentity other =
        new ReleaseIdentity(
            release.candidate, release.manifest, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    assertEquals(release.digest(), other.digest());
    other.candidateRunId = "not-a-run";
    assertThrows(IllegalArgumentException.class, other::validate);
  }

  @Test
  public void fixedPlatformSetIsRequired() {
    ReleaseIdentity release = ReleaseFixtures.release();
    release.manifest.artifacts.remove(0);
    release.manifestSha256 = release.manifest.digest();
    assertThrows(IllegalArgumentException.class, release::validate);
  }

  @Test
  public void emergencyReplacementManifestCannotMixBuildAttempts() {
    ReleaseIdentity original = ReleaseFixtures.release();
    String replacement = String.format("%064x", 99);
    ArrayList<ArtifactEntry> emergency = new ArrayList<>();
    for (ArtifactEntry artifact : original.manifest.artifacts) {
      emergency.add(
          new ArtifactEntry(
              artifact.name,
              artifact.sha256,
              artifact.size,
              "sdk-java/emergency-artifacts/"
                  + original.candidate.digest()
                  + "/"
                  + replacement
                  + "/"
                  + artifact.name));
    }
    new ReleaseIdentity(original.candidate, new ArtifactManifest(emergency)).validate();
    emergency.get(0).storageKey = original.manifest.artifacts.get(0).storageKey;
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReleaseIdentity(original.candidate, new ArtifactManifest(emergency)));
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
    assertEquals("graalvm-community", ReleasePolicy.NATIVE_JAVA_DISTRIBUTION);
    assertEquals("23", ReleasePolicy.NATIVE_JAVA_VERSION);
    assertTrue(ReleasePolicy.MAVEN_ARTIFACTS.contains("temporal-sdk"));
    assertTrue(ReleasePolicy.MAVEN_ARTIFACTS.contains("temporal-workflowstreams"));
    for (String policy :
        Arrays.asList(
            ReleasePolicy.MAVEN_POLICY_CURRENT,
            ReleasePolicy.MAVEN_POLICY_CLASSIC,
            ReleasePolicy.MAVEN_POLICY_CLASSIC_ALPHA,
            ReleasePolicy.MAVEN_POLICY_CLASSIC_ALPHA_LITE)) {
      assertEquals(
          policy, ReleasePolicy.mavenPolicyForProjects(ReleasePolicy.mavenArtifacts(policy)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> ReleasePolicy.mavenPolicyForProjects(Collections.singletonList("temporal-sdk")));
  }

  @Test
  public void approvalIsBoundToTheRecordedGithubIssue() {
    ReleaseIdentity release = ReleaseFixtures.release();
    String workflowId = QueueNames.releaseWorkflowId(release);
    String runId = "11111111-2222-3333-4444-555555555555";
    ApprovalRequest request =
        new ApprovalRequest(
            CandidateIdentity.REPOSITORY,
            release.digest(),
            workflowId,
            runId,
            100,
            42,
            "ISSUE_node_42",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            release.candidate.trustedAutomationCommit);
    ApprovalEvidence exact =
        new ApprovalEvidence(
            CandidateIdentity.REPOSITORY,
            release.digest(),
            workflowId,
            runId,
            100,
            "release-manager",
            42,
            "ISSUE_node_42",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            release.candidate.trustedAutomationCommit);
    ApprovalEvidence replay =
        new ApprovalEvidence(
            CandidateIdentity.REPOSITORY,
            release.digest(),
            workflowId,
            runId,
            99,
            "release-manager",
            43,
            "ISSUE_node_43",
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            release.candidate.trustedAutomationCommit);
    ApprovalRequest retriedRequest =
        new ApprovalRequest(
            CandidateIdentity.REPOSITORY,
            release.digest(),
            workflowId,
            runId,
            101,
            42,
            "ISSUE_node_42",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            release.candidate.trustedAutomationCommit);
    assertTrue(request.matches(exact));
    assertTrue(!request.matches(replay));
    assertTrue(request.sameIssue(retriedRequest));
  }
}
