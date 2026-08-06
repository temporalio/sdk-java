package io.temporal.releaseautomation;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ApprovalEvidence {
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9-]{1,39}");
  private static final Pattern WORKFLOW_ID = Pattern.compile("sdk-java-release/[0-9a-f]{64}");
  private static final Pattern RUN_ID = Pattern.compile("[0-9a-fA-F-]{16,64}");

  public String releaseDigest;
  public String workflowId;
  public String runId;
  public long githubApprovalRunId;
  public String githubActor;
  public long githubIssueNumber;
  public String githubIssueNodeId;
  public String githubIssueBodySha256;
  public String trustedWorkerCommit;

  public ApprovalEvidence() {}

  public ApprovalEvidence(
      String releaseDigest,
      String workflowId,
      String runId,
      long githubApprovalRunId,
      String githubActor,
      long githubIssueNumber,
      String githubIssueNodeId,
      String githubIssueBodySha256,
      String trustedWorkerCommit) {
    this.releaseDigest = releaseDigest.toLowerCase(Locale.ROOT);
    this.workflowId = workflowId;
    this.runId = runId;
    this.githubApprovalRunId = githubApprovalRunId;
    this.githubActor = githubActor;
    this.githubIssueNumber = githubIssueNumber;
    this.githubIssueNodeId = githubIssueNodeId;
    this.githubIssueBodySha256 = githubIssueBodySha256;
    this.trustedWorkerCommit = trustedWorkerCommit.toLowerCase(Locale.ROOT);
    validate();
  }

  public void validate() {
    if (releaseDigest == null || !releaseDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Approval release digest is invalid.");
    }
    if (workflowId == null || !WORKFLOW_ID.matcher(workflowId).matches()) {
      throw new IllegalArgumentException("Approval workflow ID is invalid.");
    }
    if (runId == null || !RUN_ID.matcher(runId).matches()) {
      throw new IllegalArgumentException("Approval run ID is invalid.");
    }
    if (githubApprovalRunId <= 0) {
      throw new IllegalArgumentException("GitHub approval run ID is invalid.");
    }
    if (githubActor == null || !ACTOR.matcher(githubActor).matches()) {
      throw new IllegalArgumentException("GitHub approval actor is invalid.");
    }
    if (githubIssueNumber <= 0
        || githubIssueNodeId == null
        || !githubIssueNodeId.matches("[A-Za-z0-9_=-]{8,128}")
        || githubIssueBodySha256 == null
        || !githubIssueBodySha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("GitHub approval issue identity is invalid.");
    }
    if (trustedWorkerCommit == null || !SHA.matcher(trustedWorkerCommit).matches()) {
      throw new IllegalArgumentException("Trusted worker commit must be a full SHA.");
    }
  }
}
