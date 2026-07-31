package io.temporal.releaseautomation;

public final class ReleaseStatus {
  public String phase;
  public ReleaseIdentity identity;
  public ApprovalEvidence approval;

  public ReleaseStatus() {}

  public ReleaseStatus(String phase, ReleaseIdentity identity, ApprovalEvidence approval) {
    this.phase = phase;
    this.identity = identity;
    this.approval = approval;
  }
}
