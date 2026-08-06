package io.temporal.releaseautomation;

public final class OwnershipStatus {
  public String tag;
  public String commitSha;
  public String releaseDigest;
  public String owner;
  public String githubActor;
  public long githubRunId;
  public long recordedAtMillis;
  public String manualMavenState;
  public String manualMavenActor;
  public long manualMavenRunId;

  public OwnershipStatus() {}

  OwnershipStatus(OwnershipClaim claim, long recordedAtMillis) {
    this.tag = claim.tag;
    this.commitSha = claim.commitSha;
    this.releaseDigest = claim.releaseDigest;
    this.owner = claim.owner;
    this.githubActor = claim.githubActor;
    this.githubRunId = claim.githubRunId;
    this.recordedAtMillis = recordedAtMillis;
    this.manualMavenState = "MANUAL".equals(claim.owner) ? "NOT_STARTED" : "";
  }
}
