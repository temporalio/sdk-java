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
  public String githubDraftUrl;
  public String githubReleaseUrl;
  public int mavenSubmissionGeneration;
  public int stageAttempt;
  public long stageStartedAtMillis;
  public long nextRetryAtMillis;

  public ReleaseStatus() {}

  public ReleaseStatus(
      String phase,
      ReleaseIdentity identity,
      ApprovalRequest approvalRequest,
      ApprovalEvidence approval,
      ControlEvidence control,
      String pausedFrom,
      String lastCompletedStage,
      String lastError,
      long blockedAtMillis,
      String mavenCentralUrl,
      String sonatypeRepositoryId,
      String githubDraftUrl,
      String githubReleaseUrl,
      int mavenSubmissionGeneration,
      int stageAttempt,
      long stageStartedAtMillis,
      long nextRetryAtMillis) {
    this.phase = phase;
    this.identity = identity;
    this.approvalRequest = approvalRequest;
    this.approval = approval;
    this.control = control;
    this.pausedFrom = pausedFrom;
    this.lastCompletedStage = lastCompletedStage;
    this.lastError = lastError;
    this.blockedAtMillis = blockedAtMillis;
    this.mavenCentralUrl = mavenCentralUrl;
    this.sonatypeRepositoryId = sonatypeRepositoryId;
    this.githubDraftUrl = githubDraftUrl;
    this.githubReleaseUrl = githubReleaseUrl;
    this.mavenSubmissionGeneration = mavenSubmissionGeneration;
    this.stageAttempt = stageAttempt;
    this.stageStartedAtMillis = stageStartedAtMillis;
    this.nextRetryAtMillis = nextRetryAtMillis;
  }
}
