package io.temporal.releaseautomation;

import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.Workflow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CandidateWorkflowImpl implements CandidateWorkflow {
  static final String STATUS_MEMO_KEY = "CandidateStatus";
  private CandidateIdentity candidate;
  private final List<GithubArtifactReceipt> artifacts = new ArrayList<>();
  private final List<String> pendingPlatforms = new ArrayList<>();
  private ReleaseIdentity releaseIdentity;

  @Override
  public ReleaseIdentity prepare(CandidateIdentity candidateIdentity) {
    candidateIdentity.validate();
    candidate = candidateIdentity;
    pendingPlatforms.addAll(ReleasePolicy.NATIVE_PLATFORMS);
    upsertStatus();
    Workflow.await(() -> pendingPlatforms.isEmpty());
    releaseIdentity =
        new ReleaseIdentity(
            candidateIdentity, new ArtifactManifest(artifacts), Workflow.getInfo().getRunId());
    // Visibility can now discover and start a Worker for the child queue before the child's
    // first Workflow Task has written its own status memo.
    upsertStatus();
    ReleaseWorkflow child =
        Workflow.newChildWorkflowStub(
            ReleaseWorkflow.class,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId(QueueNames.releaseWorkflowId(releaseIdentity))
                .setTaskQueue(QueueNames.releaseWorkflow(releaseIdentity))
                .setMemo(
                    Collections.singletonMap(
                        ReleaseWorkflowImpl.IDENTITY_MEMO_KEY, releaseIdentity))
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                .build());
    Async.function(child::release, releaseIdentity);
    Workflow.getWorkflowExecution(child).get();
    return releaseIdentity;
  }

  @Override
  public CandidateStatus recordArtifact(String platform, GithubArtifactReceipt artifact) {
    validateArtifact(platform, artifact);
    if (pendingPlatforms.remove(platform)) {
      artifacts.add(artifact);
    }
    upsertStatus();
    return status();
  }

  @UpdateValidatorMethod(updateName = "recordArtifact")
  public void validateArtifact(String platform, GithubArtifactReceipt artifact) {
    if (candidate == null) {
      throw new IllegalStateException("Candidate is not waiting for this native platform.");
    }
    artifact.validate();
    String expectedFile = ReleasePolicy.nativeArtifactName(candidate.version(), platform);
    if (!ReleasePolicy.githubNativeArtifactName(candidate, platform).equals(artifact.artifactName)
        || artifact.files.size() != 1
        || !expectedFile.equals(artifact.files.get(0).name)) {
      throw new IllegalArgumentException("GitHub artifact does not match the native platform.");
    }
    if (!pendingPlatforms.contains(platform)) {
      for (GithubArtifactReceipt existing : artifacts) {
        if (existing.artifactName.equals(artifact.artifactName)
            && existing.canonicalForm().equals(artifact.canonicalForm())) {
          return;
        }
      }
      throw new IllegalStateException("Candidate already recorded another native artifact.");
    }
  }

  @Override
  public CandidateIdentity candidate() {
    return candidate;
  }

  private CandidateStatus status() {
    return new CandidateStatus(candidate, pendingPlatforms, artifacts, releaseIdentity);
  }

  private void upsertStatus() {
    Workflow.upsertMemo(Collections.singletonMap(STATUS_MEMO_KEY, status()));
  }
}
