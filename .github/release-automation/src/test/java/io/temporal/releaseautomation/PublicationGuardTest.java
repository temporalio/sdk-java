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
            "abcdefabcdefabcdefabcdefabcdefabcdefabcd");
    PublicationInput input = new PublicationInput(release, approval, workflowId, runId);
    ActivityInfo info = mock(ActivityInfo.class);
    when(info.getWorkflowId()).thenReturn(workflowId);
    when(info.getWorkflowRunId()).thenReturn(runId);
    when(info.getActivityTaskQueue()).thenReturn(QueueNames.publication(release));
    Map<String, String> env = expectations(input);
    PublicationGuard.validate(input, info, env);

    env.put("EXPECTED_COMMIT_SHA", "ffffffffffffffffffffffffffffffffffffffff");
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
    env.put("TRUSTED_WORKER_COMMIT", input.approval.trustedWorkerCommit);
    return env;
  }
}
