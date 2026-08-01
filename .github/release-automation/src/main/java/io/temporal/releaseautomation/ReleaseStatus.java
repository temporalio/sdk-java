package io.temporal.releaseautomation;

public final class ReleaseStatus {
  public String phase;
  public ReleaseIdentity identity;
  public ApprovalRequest approvalRequest;
  public ApprovalEvidence approval;
  public ControlEvidence control;
  public String pausedFrom;
  public String lastCompletedStage;

  public ReleaseStatus() {}

  public ReleaseStatus(
      String phase,
      ReleaseIdentity identity,
      ApprovalRequest approvalRequest,
      ApprovalEvidence approval,
      ControlEvidence control,
      String pausedFrom,
      String lastCompletedStage) {
    this.phase = phase;
    this.identity = identity;
    this.approvalRequest = approvalRequest;
    this.approval = approval;
    this.control = control;
    this.pausedFrom = pausedFrom;
    this.lastCompletedStage = lastCompletedStage;
  }
}
