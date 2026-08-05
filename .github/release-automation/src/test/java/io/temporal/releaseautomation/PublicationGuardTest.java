package io.temporal.releaseautomation;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import io.temporal.activity.ActivityInfo;
import org.junit.Test;

public class PublicationGuardTest {
  @Test
  public void validatesOneExactPrivilegedInput() {
    PublicationInput input = input(0);
    PublicationInput expected = copy(input);
    ActivityInfo info = activity(input);

    PublicationGuard.validate(
        input, expected, info, input.release.candidate.trustedAutomationCommit);

    input.approval.githubActor = "another-manager";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PublicationGuard.validate(
                input, expected, info, input.release.candidate.trustedAutomationCommit));
  }

  @Test
  public void mavenRetryRequiresTheExactProtectedAuthorization() {
    PublicationInput input = input(1);
    input.mavenRetryAuthorization = authorization(input);
    PublicationInput expected = copy(input);
    ActivityInfo info = activity(input);

    PublicationGuard.validate(
        input, expected, info, input.release.candidate.trustedAutomationCommit);

    input.mavenRetryAuthorization = null;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PublicationGuard.validate(
                input, expected, info, input.release.candidate.trustedAutomationCommit));
  }

  private static PublicationInput input(int generation) {
    ReleaseIdentity release = ReleaseFixtures.release();
    String workflowId = QueueNames.releaseWorkflowId(release);
    String runId = "11111111-2222-3333-4444-555555555555";
    ApprovalEvidence approval =
        new ApprovalEvidence(
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
    input.mavenSubmissionGeneration = generation;
    return input;
  }

  private static ControlEvidence authorization(PublicationInput input) {
    ControlEvidence authorization = new ControlEvidence();
    authorization.action = "retry-maven-submission";
    authorization.releaseDigest = input.release.digest();
    authorization.workflowId = input.workflowId;
    authorization.runId = input.runId;
    authorization.githubRunId = 5678;
    authorization.githubActor = "release-manager";
    authorization.tag = input.release.candidate.tag;
    authorization.commitSha = input.release.candidate.commitSha;
    authorization.reason = "Protected test authorization.";
    authorization.mavenSubmissionGeneration = input.mavenSubmissionGeneration;
    authorization.authorizationSha256 =
        "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    return authorization;
  }

  private static ActivityInfo activity(PublicationInput input) {
    ActivityInfo info = mock(ActivityInfo.class);
    when(info.getWorkflowId()).thenReturn(input.workflowId);
    when(info.getWorkflowRunId()).thenReturn(input.runId);
    when(info.getActivityTaskQueue())
        .thenReturn(QueueNames.publication(input.release, input.mavenSubmissionGeneration));
    return info;
  }

  private static PublicationInput copy(PublicationInput input) {
    Gson gson = new Gson();
    return gson.fromJson(gson.toJson(input), PublicationInput.class);
  }
}
