package io.temporal.releaseautomation;

import java.util.ArrayList;
import java.util.List;

public final class ReleaseStatus {
  public String phase;
  public ReleaseIdentity identity;
  public ApprovalRequest approvalRequest;
  public ApprovalEvidence approval;
  public ControlEvidence control;
  public String pausedFrom;
  public String handedOffFrom;
  public String lastCompletedStage;
  public String lastError;
  public long blockedAtMillis;
  public String mavenCentralUrl;
  public String sonatypeRepositoryId;
  public String portalDeploymentId;
  public String githubDraftUrl;
  public String githubReleaseUrl;
  public int mavenSubmissionGeneration;
  public ControlEvidence mavenRetryAuthorization;
  public GithubArtifactReceipt mavenPayload;
  public List<MavenGenerationState> mavenGenerations = new ArrayList<>();
  public OwnershipStatus ownership;
  public int stageAttempt;
  public long stageStartedAtMillis;
  public long nextRetryAtMillis;

  public ReleaseStatus() {}
}
