package io.temporal.releaseautomation;

public final class ReleaseStatus {
  public String phase;
  public ReleaseIdentity identity;
  public ApprovalRequest approvalRequest;
  public ApprovalEvidence approval;
  public ControlEvidence control;
  public String pausedFrom;
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
  public int stageAttempt;
  public long stageStartedAtMillis;
  public long nextRetryAtMillis;

  public ReleaseStatus() {}
}
