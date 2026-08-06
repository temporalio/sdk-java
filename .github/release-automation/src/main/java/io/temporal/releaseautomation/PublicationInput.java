package io.temporal.releaseautomation;

import java.util.ArrayList;
import java.util.List;

public final class PublicationInput {
  public ReleaseIdentity release;
  public ApprovalEvidence approval;
  public ApprovalRequest approvalRequest;
  public String workflowId;
  public String runId;
  public int mavenSubmissionGeneration;
  public ControlEvidence mavenRetryAuthorization;
  public GithubArtifactReceipt mavenPayload;
  public List<MavenGenerationState> mavenGenerations = new ArrayList<>();

  public PublicationInput() {}

  public PublicationInput(
      ReleaseIdentity release,
      ApprovalRequest approvalRequest,
      ApprovalEvidence approval,
      String workflowId,
      String runId) {
    this.release = release;
    this.approvalRequest = approvalRequest;
    this.approval = approval;
    this.workflowId = workflowId;
    this.runId = runId;
  }
}
