package io.temporal.releaseautomation;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.activity.ActivityInfo;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class PublicationGuardTest {
  @Test
  public void validatesEveryPrivilegedExpectation() {
    ReleaseIdentity release = ReleaseFixtures.release();
    String workflowId = QueueNames.releaseWorkflowId(release);
    String runId = "11111111-2222-3333-4444-555555555555";
    ApprovalEvidence approval =
        new ApprovalEvidence(
            CandidateIdentity.REPOSITORY,
            release.digest(),
            workflowId,
            runId,
            1234,
            "release-manager",
            42,
            "ISSUE_node_42",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "abcdefabcdefabcdefabcdefabcdefabcdefabcd");
    PublicationInput input = new PublicationInput(release, approval, workflowId, runId);
    ActivityInfo info = mock(ActivityInfo.class);
    when(info.getWorkflowId()).thenReturn(workflowId);
    when(info.getWorkflowRunId()).thenReturn(runId);
    when(info.getActivityTaskQueue()).thenReturn(QueueNames.publication(release));
    Map<String, String> env = expectations(input);
    PublicationGuard.validate(input, info, env);

    input.candidateDigest = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
    assertThrows(IllegalArgumentException.class, () -> PublicationGuard.validate(input, info, env));
    input.candidateDigest = release.candidate.digest();

    env.put("EXPECTED_COMMIT_SHA", "ffffffffffffffffffffffffffffffffffffffff");
    assertThrows(IllegalArgumentException.class, () -> PublicationGuard.validate(input, info, env));
  }

  @Test
  public void mavenRetryRequiresTheProtectedAuthorizationBinding() {
    ReleaseIdentity release = ReleaseFixtures.release();
    String workflowId = QueueNames.releaseWorkflowId(release);
    String runId = "11111111-2222-3333-4444-555555555555";
    ApprovalEvidence approval =
        new ApprovalEvidence(
            CandidateIdentity.REPOSITORY,
            release.digest(),
            workflowId,
            runId,
            1234,
            "release-manager",
            42,
            "ISSUE_node_42",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            release.candidate.trustedAutomationCommit);
    PublicationInput input = new PublicationInput(release, approval, workflowId, runId);
    input.mavenSubmissionGeneration = 1;
    ControlEvidence authorization = new ControlEvidence();
    authorization.action = "retry-maven-submission";
    authorization.repository = CandidateIdentity.REPOSITORY;
    authorization.releaseDigest = release.digest();
    authorization.workflowId = workflowId;
    authorization.runId = runId;
    authorization.githubRunId = 5678;
    authorization.githubActor = "release-manager";
    authorization.tag = release.candidate.tag;
    authorization.commitSha = release.candidate.commitSha;
    authorization.reason = "Protected test authorization.";
    authorization.mavenSubmissionGeneration = 1;
    authorization.authorizationSha256 =
        "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    input.mavenRetryAuthorization = authorization;
    ActivityInfo info = mock(ActivityInfo.class);
    when(info.getWorkflowId()).thenReturn(workflowId);
    when(info.getWorkflowRunId()).thenReturn(runId);
    when(info.getActivityTaskQueue()).thenReturn(QueueNames.publication(release, 1));
    Map<String, String> env = expectations(input);
    env.put("EXPECTED_MAVEN_SUBMISSION_GENERATION", "1");
    env.put("EXPECTED_MAVEN_RETRY_AUTHORIZATION_SHA256", authorization.authorizationSha256);
    PublicationGuard.validate(input, info, env);

    input.mavenRetryAuthorization = null;
    assertThrows(IllegalArgumentException.class, () -> PublicationGuard.validate(input, info, env));
  }

  private static Map<String, String> expectations(PublicationInput input) {
    Map<String, String> env = new HashMap<>();
    env.put("EXPECTED_WORKFLOW_ID", input.workflowId);
    env.put("EXPECTED_RUN_ID", input.runId);
    env.put("EXPECTED_REPOSITORY", input.release.candidate.repository);
    env.put("EXPECTED_TAG", input.release.candidate.tag);
    env.put("EXPECTED_COMMIT_SHA", input.release.candidate.commitSha);
    env.put("EXPECTED_NOTES_SHA256", input.release.candidate.releaseNotesSha256);
    env.put("EXPECTED_MANIFEST_SHA256", input.release.manifestSha256);
    env.put("EXPECTED_RELEASE_DIGEST", input.release.digest());
    env.put("EXPECTED_APPROVAL_RUN_ID", Long.toString(input.approval.githubApprovalRunId));
    env.put("EXPECTED_APPROVAL_ACTOR", input.approval.githubActor);
    env.put("EXPECTED_APPROVAL_ISSUE_NUMBER", Long.toString(input.approval.githubIssueNumber));
    env.put("EXPECTED_APPROVAL_ISSUE_NODE_ID", input.approval.githubIssueNodeId);
    env.put("EXPECTED_APPROVAL_ISSUE_BODY_SHA256", input.approval.githubIssueBodySha256);
    env.put("TRUSTED_WORKER_COMMIT", input.approval.trustedWorkerCommit);
    env.put("EXPECTED_MAVEN_SUBMISSION_GENERATION", "0");
    return env;
  }
}
