package io.temporal.releaseautomation;

public final class PublicationInput {
  public ReleaseIdentity release;
  public ApprovalEvidence approval;
  public String workflowId;
  public String runId;
  public int mavenSubmissionGeneration;
  public ControlEvidence mavenRetryAuthorization;

  public PublicationInput() {}

  public PublicationInput(
      ReleaseIdentity release, ApprovalEvidence approval, String workflowId, String runId) {
    this.release = release;
    this.approval = approval;
    this.workflowId = workflowId;
    this.runId = runId;
  }
}
