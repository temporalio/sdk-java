package io.temporal.releaseautomation;

public final class ApprovalRequest {
  public String repository;
  public String releaseDigest;
  public String workflowId;
  public String runId;
  public long githubRunId;
  public long githubIssueNumber;
  public String githubIssueNodeId;
  public String githubIssueBodySha256;
  public String trustedWorkerCommit;

  public ApprovalRequest() {}

  public ApprovalRequest(
      String repository,
      String releaseDigest,
      String workflowId,
      String runId,
      long githubRunId,
      long githubIssueNumber,
      String githubIssueNodeId,
      String githubIssueBodySha256,
      String trustedWorkerCommit) {
    this.repository = repository;
    this.releaseDigest = releaseDigest;
    this.workflowId = workflowId;
    this.runId = runId;
    this.githubRunId = githubRunId;
    this.githubIssueNumber = githubIssueNumber;
    this.githubIssueNodeId = githubIssueNodeId;
    this.githubIssueBodySha256 = githubIssueBodySha256;
    this.trustedWorkerCommit = trustedWorkerCommit;
    validate();
  }

  public void validate() {
    if (!ReleasePolicy.REPOSITORY.equals(repository)
        || releaseDigest == null
        || !releaseDigest.matches("[0-9a-f]{64}")
        || workflowId == null
        || !workflowId.matches("sdk-java-release/[0-9a-f]{64}")
        || runId == null
        || !runId.matches("[0-9a-fA-F-]{16,64}")
        || githubRunId <= 0
        || githubIssueNumber <= 0
        || githubIssueNodeId == null
        || !githubIssueNodeId.matches("[A-Za-z0-9_=-]{8,128}")
        || githubIssueBodySha256 == null
        || !githubIssueBodySha256.matches("[0-9a-f]{64}")
        || trustedWorkerCommit == null
        || !trustedWorkerCommit.matches("[0-9a-f]{40}")) {
      throw new IllegalArgumentException("Invalid release-specific approval request.");
    }
  }

  public boolean matches(ApprovalEvidence evidence) {
    validate();
    evidence.validate();
    return repository.equals(evidence.repository)
        && releaseDigest.equals(evidence.releaseDigest)
        && workflowId.equals(evidence.workflowId)
        && runId.equals(evidence.runId)
        && githubIssueNumber == evidence.githubIssueNumber
        && githubIssueNodeId.equals(evidence.githubIssueNodeId)
        && githubIssueBodySha256.equals(evidence.githubIssueBodySha256)
        && trustedWorkerCommit.equals(evidence.trustedWorkerCommit);
  }
}
